package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoduellog.InMemoryDuelLog;
import com.github.coldgust.ygoduellog.JsonlSink;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import com.github.coldgust.ygoduellog.query.DuelLog;
import com.github.coldgust.ygoprosdk.core.RequestDecoder;
import com.github.coldgust.ygoprosdk.core.ResponseEncoder;
import com.github.coldgust.ygoprosdk.core.StateProjector;
import com.github.coldgust.ygoprosdk.core.ZoneMapper;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;
import com.github.coldgust.ygoprosdk.protocol.ByteWriter;
import com.github.coldgust.ygoprosdk.protocol.FrameCodec;
import com.github.coldgust.ygoprosdk.protocol.MsgFraming;
import com.github.coldgust.ygoprosdk.protocol.ProtocolIds;
import com.github.coldgust.ygoprosdk.transport.NettyTcpTransport;
import com.github.coldgust.ygoprosdk.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.HexFormat;

/**
 * 对局会话：Agent 的操作门面（规范 §10）。
 *
 * <h2>Agent 使用方式（阻塞拉取 + 监听）</h2>
 * <pre>{@code
 * DuelSession session = DuelSession.start(config);       // 连接 + 进房
 * session.uploadDeck(deck);                              // 卡组
 * session.setReady(true);                                // 就绪
 * session.awaitDuelStart();                              // 等开局
 * while (!session.state().isFinished()) {
 *     ChoiceRequest request = session.awaitChoice();     // 下一步动作集合
 *     ChoiceSelection decision = agent.decide(session.state(), request);
 *     session.respond(decision);                         // 提交决策
 * }
 * session.close();
 * }</pre>
 *
 * <h2>SDK 自动处理的协议事务（Agent 不可见）</h2>
 * <ul>
 *   <li>TIME_CONFIRM 契约（§3.2）：收到"问自己"的 TIME_LIMIT 先发 0x15；</li>
 *   <li>座位校准（§7.3.2）：以 MSG_START 首字节为准；</li>
 *   <li>MSG_RETRY：按保底策略重答（§10.5）；</li>
 *   <li>决策超时保底（§10.6）、看门狗告警、未知消息跳过、应答串行化。</li>
 * </ul>
 */
public final class DuelSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DuelSession.class);

    /** -Dygo.debug.raw=true 时逐条打印内层 MSG 的十六进制转储（协议对账用）。 */
    private static final boolean RAW_DEBUG = Boolean.getBoolean("ygo.debug.raw");

    /** 会话阶段。 */
    public enum Stage { CONNECTING, LOBBY, DUEL, ENDED, CLOSED }

    private final SessionConfig config;
    private final Transport transport;
    private final StateProjector projector;
    private final RequestDecoder decoder;
    private final FallbackPolicy fallback;
    private final List<DuelListener> listeners = new CopyOnWriteArrayList<>();

    /** 对局事件记录（始终开启内存记录；可按配置落盘）。 */
    private final InMemoryDuelLog duelLog = new InMemoryDuelLog();

    private final ByteArrayOutputStream accum = new ByteArrayOutputStream(8192);
    private final Object sendLock = new Object();
    private final BlockingQueue<ChoiceRequest> choiceQueue = new LinkedBlockingQueue<>();
    private final CountDownLatch duelStartLatch = new CountDownLatch(1);
    private final CountDownLatch duelEndLatch = new CountDownLatch(1);
    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ygo-frame-", 0).factory());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ygo-watchdog-", 0).factory());

    private volatile Stage stage = Stage.CONNECTING;
    private volatile int mySeat = -1;
    private volatile int roomTimer;
    private volatile boolean deckUploaded;
    private volatile Deck pendingDeck;
    private volatile boolean wantReady;
    private volatile boolean toDuelistSent;
    private volatile ChoiceRequest pendingRequest;
    private volatile ChoiceRequest lastRequest;
    private volatile long pendingSinceMillis;
    private volatile int retryCount;
    private volatile DuelResult result;

    private DuelSession(SessionConfig config) {
        this.config = config;
        this.transport = new NettyTcpTransport(config.host(), config.port());
        this.projector = new StateProjector(config.cardResolver());
        this.decoder = new RequestDecoder(new MirrorViewImpl());
        this.fallback = config.fallbackPolicy() != null ? config.fallbackPolicy() : DefaultFallbackPolicy.get();
        // 对局记录桥注册在首位，保证状态 diff 先于用户监听器
        this.listeners.add(new DuelLogBridge(duelLog, config.playerName(), config.duelLogDecisionContext()));
        if (config.duelLogPath() != null) {
            duelLog.attachSink(new JsonlSink(config.duelLogPath()));
            log.info("对局记录落盘：{}", config.duelLogPath());
        }
    }

    /**
     * 建立连接并完成进房（建房或加入）。
     */
    public static DuelSession start(SessionConfig config) throws Exception {
        DuelSession session = new DuelSession(config);
        session.transport.connect(new TransportListener(session));
        session.stage = Stage.LOBBY;
        session.sendPlayerInfo();
        if (config.createRoom()) {
            session.sendCreateGame(config.roomOptions());
            log.info("已建房 {}:{}（模式 {}）", config.host(), config.port(),
                    config.roomOptions() == null ? 0 : config.roomOptions().mode());
        } else {
            session.sendJoinGame();
            log.info("已发送进房请求 {}:{}，等待房间", config.host(), config.port());
        }
        session.watchdog.scheduleWithFixedDelay(session::watchdogTick, 1, 1, TimeUnit.SECONDS);
        return session;
    }

    /* ================= 大厅 API ================= */

    /**
     * 上传卡组（规范 §6：main+extra 合并计数 + side 计数）。
     * 若当前是观战位（未分配座位），先记住意图，转成决斗者后自动补发。
     */
    public void uploadDeck(Deck deck) throws Exception {
        pendingDeck = deck;
        if (mySeat == 0 || mySeat == 1) {
            doUploadDeck(deck);
        } else {
            log.info("当前非决斗位（座位 {}），卡组暂存，转决斗者后自动上传", mySeat);
        }
    }

    private void doUploadDeck(Deck deck) throws Exception {
        ByteWriter w = new ByteWriter();
        w.u32(deck.main().size() + deck.extra().size());
        w.u32(deck.side().size());
        for (long code : deck.main()) {
            w.u32(code);
        }
        for (long code : deck.extra()) {
            w.u32(code);
        }
        for (long code : deck.side()) {
            w.u32(code);
        }
        sendFrame(ProtocolIds.CTOS_UPDATE_DECK, w.toByteArray());
        deckUploaded = true;
        log.info("卡组已上传：main+extra={}，side={}", deck.main().size() + deck.extra().size(), deck.side().size());
    }

    /** 就绪 / 取消就绪。注意：卡组非法时服务端静默忽略就绪请求（§5.4）。 */
    public void setReady(boolean ready) throws Exception {
        wantReady = ready;
        if (!deckUploaded) {
            if (ready) {
                log.info("卡组尚未上传，就绪意图已记录，上传后自动就绪");
            }
            return;
        }
        sendFrame(ready ? ProtocolIds.CTOS_HS_READY : ProtocolIds.CTOS_HS_NOTREADY);
    }

    /** 座位就位后的补发：卡组 → 就绪。 */
    private void flushPendingLobbyActions() {
        try {
            Deck deck = pendingDeck;
            if (deck != null && !deckUploaded) {
                doUploadDeck(deck);
            }
            if (wantReady && deckUploaded) {
                sendFrame(ProtocolIds.CTOS_HS_READY);
                log.info("已自动就绪");
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /** 开局（仅房主）。 */
    public void startDuel() throws Exception {
        sendFrame(ProtocolIds.CTOS_HS_START);
    }

    /** 等待对局开始。 */
    public void awaitDuelStart() throws InterruptedException {
        duelStartLatch.await();
    }

    /** 等待对局开始（带超时）。 */
    public boolean awaitDuelStart(Duration timeout) throws InterruptedException {
        return duelStartLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /* ================= 决策交互 API ================= */

    /**
     * 阻塞获取下一个"问自己"的请求。
     */
    public ChoiceRequest awaitChoice() throws InterruptedException {
        return choiceQueue.take();
    }

    /** 带超时版本；超时抛 {@link TimeoutException}。 */
    public ChoiceRequest awaitChoice(Duration timeout) throws InterruptedException, TimeoutException {
        ChoiceRequest request = choiceQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (request == null) {
            throw new TimeoutException("等待决策请求超时");
        }
        return request;
    }

    /** 立即取出已到达的请求（无则返回 null）。 */
    public ChoiceRequest pollChoice() {
        return choiceQueue.poll();
    }

    /**
     * 提交决策（编码为 CTOS_RESPONSE）。决策必须引用当前请求给出的候选对象。
     */
    public void respond(ChoiceSelection selection) throws Exception {
        ChoiceRequest request = pendingRequest;
        if (request == null) {
            throw new IllegalStateException("当前没有待应答的请求");
        }
        if (config.answerDelayMillis() > 0) {
            Thread.sleep(config.answerDelayMillis());
        }
        byte[] payload = ResponseEncoder.encode(request, selection, mySeat);
        sendFrame(ProtocolIds.CTOS_RESPONSE, payload);
        retryCount = 0;
        pendingRequest = null;
        lastRequest = request;
        notifyResponseSent(request, selection, ResponseSource.PLAYER, 0);
        log.debug("应答 {} -> {} 字节", request.getClass().getSimpleName(), payload.length);
    }

    /** 对局事件记录查询接口（始终可用，实时更新）。 */
    public DuelLog duelLog() {
        return duelLog;
    }

    /** 当前战场镜像快照（不可变）。 */
    public DuelState state() {
        return projector.snapshot();
    }

    /** 请求全场快照对账（规范 §9.4；仅对局中、玩家身份有效）。 */
    public void requestFieldSnapshot() throws Exception {
        if (stage != Stage.DUEL) {
            throw new IllegalStateException("快照请求仅在对局中可用");
        }
        sendFrame(ProtocolIds.CTOS_REQUEST_FIELD);
    }

    /** 投降。 */
    public void surrender() throws Exception {
        sendFrame(ProtocolIds.CTOS_SURRENDER);
    }

    /** 等待对局结束。 */
    public DuelResult awaitDuelEnd(Duration timeout) throws InterruptedException {
        duelEndLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return result;
    }

    /** 当前会话阶段。 */
    public Stage stage() {
        return stage;
    }

    /** 我方绝对座位号。 */
    public int selfSeat() {
        return mySeat;
    }

    public void addListener(DuelListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DuelListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void close() {
        if (stage == Stage.CLOSED) {
            return;
        }
        stage = Stage.CLOSED;
        watchdog.shutdownNow();
        frameExecutor.shutdownNow();
        transport.close();
        duelLog.close();
    }

    /* ================= 内部：发送 ================= */

    private void sendPlayerInfo() throws Exception {
        sendFrame(ProtocolIds.CTOS_PLAYER_INFO,
                new ByteWriter().utf16Fixed(config.playerName(), 40).toByteArray());
    }

    private void sendJoinGame() throws Exception {
        byte[] payload = new ByteWriter()
                .u16(config.joinVersion())
                .u32(0)                      // gameid
                .utf16Fixed(config.joinPassword(), 40)
                .u32(config.joinVersion())   // version2
                .toByteArray();
        sendFrame(ProtocolIds.CTOS_JOIN_GAME, payload);
    }

    private void sendCreateGame(RoomOptions o) throws Exception {
        ByteWriter w = new ByteWriter();
        w.u32(o.banlistHash());
        w.u8(o.rule());
        w.u8(o.masterRule());          // 建房顺序：masterRule 在前（规范 §5.2）
        w.u8(o.mode());
        w.u8(o.enablePriority() ? 1 : 0);
        w.u8(o.noCheckDeck() ? 1 : 0);
        w.u8(o.noShuffleDeck() ? 1 : 0);
        w.zeros(3);                    // padding
        w.i32(o.startLp());
        w.u8(o.startHand());
        w.u8(o.drawCount());
        w.i16(o.timeLimitSeconds());
        w.utf16Fixed(o.roomName(), 40);
        w.utf16Fixed(o.roomPassword(), 40);
        w.zeros(200);                  // 备注
        sendFrame(ProtocolIds.CTOS_CREATE_GAME, w.toByteArray());
    }

    private void sendFrame(int type) throws Exception {
        sendFrame(type, new byte[0]);
    }

    private void sendFrame(int type, byte[] payload) throws Exception {
        synchronized (sendLock) {
            transport.send(FrameCodec.encode(type, payload));
        }
        if (config.duelLogRawFrames()) {
            duelLog.append("SYSTEM", DuelEventKind.RAW_FRAME,
                    new DuelEventPayload.RawFrame(
                            "OUT", type, HexFormat.of().formatHex(payload)));
        }
        log.debug("CTOS {}", ProtocolIds.name(type));
    }

    /* ================= 内部：接收与分发 ================= */

    private void onBytes(byte[] data, int length) {
        accum.write(data, 0, length);
        List<FrameCodec.Frame> frames = FrameCodec.split(accum);
        if (frames.isEmpty()) {
            return;
        }
        // 串行化处理（保序）；解码较轻，直接在回调线程逐帧处理亦可，这里用单线程执行器保证顺序
        frameExecutor.execute(() -> {
            for (FrameCodec.Frame frame : frames) {
                try {
                    processFrame(frame);
                } catch (Exception e) {
                    log.error("帧处理失败 type={}", ProtocolIds.name(frame.type()), e);
                    notifyError(e);
                }
            }
        });
    }

    private void processFrame(FrameCodec.Frame frame) throws Exception {
        switch (frame.type()) {
            case ProtocolIds.STOC_GAME_MSG -> {
                if (config.duelLogRawFrames()) {
                    duelLog.append("SYSTEM", DuelEventKind.RAW_FRAME,
                            new DuelEventPayload.RawFrame(
                                    "STOC", ProtocolIds.STOC_GAME_MSG,
                                    HexFormat.of().formatHex(frame.payload())));
                }
                handleGameMsg(frame.payload());
            }
            case ProtocolIds.STOC_JOIN_GAME -> onJoinGame(frame.payload());
            case ProtocolIds.STOC_TYPE_CHANGE -> onTypeChange(frame.payload());
            case ProtocolIds.STOC_ERROR_MSG -> onErrorMsg(frame.payload());
            case ProtocolIds.STOC_SELECT_HAND -> onNeedHandResult();
            case ProtocolIds.STOC_SELECT_TP -> onNeedTpResult();
            case ProtocolIds.STOC_TIME_LIMIT -> onTimeLimit(frame.payload());
            case ProtocolIds.STOC_DUEL_START -> onDuelStart();
            case ProtocolIds.STOC_DUEL_END -> onDuelEnd();
            case ProtocolIds.STOC_FIELD_FINISH -> log.debug("全场快照接收完毕");
            case ProtocolIds.STOC_DECK_COUNT, ProtocolIds.STOC_CHAT, ProtocolIds.STOC_HS_PLAYER_ENTER,
                 ProtocolIds.STOC_HS_PLAYER_CHANGE, ProtocolIds.STOC_HS_WATCH_CHANGE,
                 ProtocolIds.STOC_REPLAY, ProtocolIds.STOC_HAND_RESULT -> {
                // 已知但不参与状态：聊天 / 大厅广播 / 回放等
            }
            default -> log.debug("未处理 STOC 0x{:02X}", frame.type());
        }
    }

    private void handleGameMsg(byte[] payload) {
        List<MsgFraming.Msg> msgs = MsgFraming.split(payload);
        for (MsgFraming.Msg msg : msgs) {
            byte[] body = new byte[msg.size() - 1];
            System.arraycopy(payload, msg.start() + 1, body, 0, body.length);
            int type = msg.type();

            if (RAW_DEBUG) {
                log.info("RAW MSG type=0x{} size={} body={}", Integer.toHexString(type),
                        msg.size(), HexFormat.of().formatHex(body));
            }
            if (config.duelLogRawFrames()) {
                duelLog.append("SYSTEM", DuelEventKind.RAW_FRAME,
                        new DuelEventPayload.RawFrame(
                                "IN", type, HexFormat.of().formatHex(body)));
            }

            if (type == ProtocolIds.MSG_START) {
                projector.apply(type, new ByteReader(body));
                mySeat = projector.selfSeat();
                choiceQueue.clear();
                retryCount = 0;
                pendingRequest = null;
                publishState();
                log.info("MSG_START：座位校准 我方={}，规则 MR{}", mySeat, state().duelRule());
                continue;
            }

            if (ProtocolIds.isRequest(type)) {
                // 归属判断只读 body[0]；解码器拿全新游标（否则 player 被读两次，整体偏移 1 字节）
                int player = body.length > 0 ? body[0] & 0xFF : -1;
                if (player != mySeat) {
                    log.debug("（{} 是问座位 {} 的，非我方，跳过）",
                            msgName(type), player);
                    continue;
                }
                if (type == ProtocolIds.MSG_ROCK_PAPER_SCISSORS) {
                    // 局内猜拳：自动应答（协议用 CTOS_HAND_RESULT，规范 §7.3.4）
                    try {
                        sendFrame(ProtocolIds.CTOS_HAND_RESULT,
                                new ByteWriter().u8(ThreadLocalRandom.current().nextInt(1, 4)).toByteArray());
                    } catch (Exception e) {
                        notifyError(e);
                    }
                    continue;
                }
                ChoiceRequest request = decoder.decode(type, new ByteReader(body));
                retryCount = 0;
                pendingRequest = request;
                pendingSinceMillis = System.currentTimeMillis();
                choiceQueue.offer(request);
                publishState();
                notifyChoice(request);
                continue;
            }

            if (type == ProtocolIds.MSG_RETRY) {
                onRetry();
                continue;
            }

            if (type == ProtocolIds.MSG_CHAINING) {
                // 效果发动在状态 diff 里不可见（卡不动），单独解码后通知监听器：
                // 对局日志据此记 EFFECT_ACTIVATED，combo 录入据此知道"用户发动了什么"。
                notifyEffectActivated(new ByteReader(body));
            }

            if (type == ProtocolIds.MSG_WIN) {
                projector.apply(type, new ByteReader(body));
                DuelState snapshot = projector.snapshot();
                result = snapshot.result();
                latestStateRef = snapshot;
                publishState();
                if (result != null) {
                    notifyDuelEnd(result);
                    duelEndLatch.countDown();
                }
                continue;
            }

            projector.apply(type, new ByteReader(body));
            publishState();
        }
    }

    private void onRetry() {
        ChoiceRequest request = pendingRequest != null ? pendingRequest : lastRequest;
        retryCount++;
        log.warn("MSG_RETRY：第 {} 次被打回，改用保底策略重答 {}", retryCount,
                request == null ? "无" : request.getClass().getSimpleName());
        if (request != null) {
            for (DuelListener l : listeners) {
                try {
                    l.onRetryIssued(request, retryCount);
                } catch (Exception e) {
                    log.warn("监听器 onRetryIssued 异常", e);
                }
            }
        }
        if (retryCount > config.retryLimit()) {
            log.error("重试超过上限 {}，主动投降", config.retryLimit());
            try {
                surrender();
            } catch (Exception e) {
                notifyError(e);
            }
            return;
        }
        if (request == null) {
            return;
        }
        try {
            ChoiceSelection fallbackAnswer = fallback.fallback(request, retryCount);
            byte[] payload = ResponseEncoder.encode(request, fallbackAnswer, mySeat);
            sendFrame(ProtocolIds.CTOS_RESPONSE, payload);
            notifyResponseSent(request, fallbackAnswer, ResponseSource.FALLBACK, retryCount);
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void notifyResponseSent(ChoiceRequest request, ChoiceSelection selection,
                                    ResponseSource source, int retryCount) {
        for (DuelListener l : listeners) {
            try {
                l.onResponseSent(request, selection, source, retryCount);
            } catch (Exception e) {
                log.warn("监听器 onResponseSent 异常", e);
            }
        }
    }

    private void onJoinGame(byte[] payload) {
        ByteReader r = new ByteReader(payload);
        int banlist = (int) r.u32();
        int rule = r.u8();
        int mode = r.u8();        // 进房顺序：mode 在前（规范 §5.2）
        int masterRule = r.u8();
        boolean noCheck = r.u8() == 1;
        boolean noShuffle = r.u8() == 1;
        r.skip(3);
        int lp = r.i32();
        int hand = r.u8();
        int draw = r.u8();
        int timer = r.i16();
        roomTimer = timer;
        log.info("进房成功：rule={} mode={} MR{} LP={} 手牌={} 抽卡={} 计时={}s 禁限表=0x{}",
                rule, mode, masterRule, lp, hand, draw, timer, Integer.toHexString(banlist));
    }

    private void onTypeChange(byte[] payload) {
        int value = payload.length > 0 ? payload[0] & 0xFF : -1;
        if (value < 0) {
            return;
        }
        // 低 4 位 = 身份（0/1 决斗者座位、7 观战）；高 4 位非 0 = 建房者/房主标志（§4.3）
        int type = value & 0x0F;
        boolean host = (value & 0xF0) != 0;
        if (type == 0 || type == 1) {
            boolean seatChanged = mySeat != type;
            mySeat = type;
            log.info("身份变更：座位 {}（决斗者{}）", type, host ? "，房主" : "");
            if (seatChanged) {
                flushPendingLobbyActions();
            }
        } else if (type == 7) {
            log.info("身份变更：观战者");
            if (stage == Stage.LOBBY && !toDuelistSent) {
                // 观战位 → 尝试转为决斗者（有决斗位空出时服务端会重新分配身份）
                toDuelistSent = true;
                try {
                    sendFrame(ProtocolIds.CTOS_HS_TODUELIST);
                    log.info("已发送 TODUELIST，尝试转为决斗者");
                } catch (Exception e) {
                    notifyError(e);
                }
            }
        } else {
            log.debug("身份值 0x{}（未识别，§4.3）", Integer.toHexString(value));
        }
    }

    private void onErrorMsg(byte[] payload) {
        if (payload.length < 1) {
            return;
        }
        int errorType = payload[0] & 0xFF;
        String kind = switch (errorType) {
            case ProtocolIds.ERROR_JOIN -> "JOIN";
            case ProtocolIds.ERROR_DECK -> "DECK";
            case ProtocolIds.ERROR_SIDE -> "SIDE";
            case ProtocolIds.ERROR_VER -> "VER";
            case ProtocolIds.ERROR_VER2 -> "VER2";
            default -> "0x" + Integer.toHexString(errorType);
        };
        // code 语义随实现而异，仅记录（规范 §5.6）
        String codeHex = payload.length >= 8
                ? Long.toHexString(new ByteReader(payload, 4).u32())
                : "?";
        notifyError(new ProtocolException("服务端错误 " + kind + "，code=0x" + codeHex + "（code 仅记日志）"));
    }

    private void onNeedHandResult() throws Exception {
        int gesture = ThreadLocalRandom.current().nextInt(1, 4); // 1/2/3
        sendFrame(ProtocolIds.CTOS_HAND_RESULT, new ByteWriter().u8(gesture).toByteArray());
        log.info("猜拳 -> 出 {}（1=剪刀 2=石头 3=布）", gesture);
    }

    private void onNeedTpResult() throws Exception {
        boolean first = config.firstPlayer() == SessionConfig.FirstMove.FIRST;
        int tpByte = first ? 1 : 0;
        sendFrame(ProtocolIds.CTOS_TP_RESULT, new ByteWriter().u8(tpByte).toByteArray());
        // 座位交换规则（§7.3.3）：随后由 MSG_START 校准
        if ((tpByte == 1 && mySeat == 1) || (tpByte == 0 && mySeat == 0)) {
            mySeat = 1 - mySeat;
            log.info("先后攻 -> 请求{}，按交换规则座位更新为 {}", first ? "先攻" : "后攻", mySeat);
        } else {
            log.info("先后攻 -> 请求{}", first ? "先攻" : "后攻");
        }
    }

    private void onTimeLimit(byte[] payload) {
        if (payload.length < 4) {
            return;
        }
        ByteReader r = new ByteReader(payload);
        int player = r.u8();
        r.skip(1);
        int seconds = r.i16();
        if (player == mySeat) {
            // TIME_CONFIRM 契约（§3.2）：必须先发，否则应答被静默丢弃
            try {
                sendFrame(ProtocolIds.CTOS_TIME_CONFIRM);
                log.debug("计时：我方 {}s，已发 TIME_CONFIRM 放行应答", seconds);
            } catch (Exception e) {
                notifyError(e);
            }
        }
    }

    private void onDuelStart() {
        if (stage == Stage.DUEL) {
            return; // 快照回包会重发 STOC_DUEL_START（§9.4），忽略
        }
        stage = Stage.DUEL;
        duelStartLatch.countDown();
        log.info("== 对局开始 ==");
    }

    private void onDuelEnd() {
        if (stage == Stage.ENDED) {
            return;
        }
        stage = Stage.ENDED;
        log.info("== 对局结束 ==");
    }

    /* ================= 看门狗（§10.6）================= */

    private void watchdogTick() {
        if (stage != Stage.DUEL || pendingRequest == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - pendingSinceMillis;
        Duration choiceTimeout = config.choiceTimeout();
        if (choiceTimeout != null && elapsed > choiceTimeout.toMillis()) {
            log.warn("决策超时 {}ms，提交保底应答（{}）", elapsed, pendingRequest.getClass().getSimpleName());
            ChoiceRequest request = pendingRequest;
            pendingRequest = null;
            try {
                ChoiceSelection fallbackAnswer = fallback.fallback(request, retryCount);
                byte[] payload = ResponseEncoder.encode(request, fallbackAnswer, mySeat);
                sendFrame(ProtocolIds.CTOS_RESPONSE, payload);
                notifyResponseSent(request, fallbackAnswer, ResponseSource.TIMEOUT_FALLBACK, retryCount);
            } catch (Exception e) {
                notifyError(e);
            }
        }
    }

    /* ================= 通知与杂项 ================= */

    private volatile DuelState latestStateRef;

    private void publishState() {
        DuelState snapshot = projector.snapshot();
        latestStateRef = snapshot;
        for (DuelListener l : listeners) {
            try {
                l.onStateUpdate(snapshot);
            } catch (Exception e) {
                log.warn("监听器 onStateUpdate 异常", e);
            }
        }
    }

    private void notifyChoice(ChoiceRequest request) {
        for (DuelListener l : listeners) {
            try {
                l.onChoiceAvailable(request);
            } catch (Exception e) {
                log.warn("监听器 onChoiceAvailable 异常", e);
            }
        }
    }

    /**
     * MSG_CHAINING：解码并发通知效果发动。字段布局见 StateProjector#onChaining：
     * code u32 / loc u32 / controller u8 / 触发位置 u8×2 / description u32 / chainIndex u8。
     */
    private void notifyEffectActivated(ByteReader r) {
        try {
            long code = r.u32();
            r.u32(); // loc
            int controller = r.u8();
            r.u8();
            r.u8();
            long desc = r.u32();
            for (DuelListener l : listeners) {
                try {
                    l.onEffectActivated(controller, code, desc);
                } catch (Exception e) {
                    log.warn("监听器 onEffectActivated 异常", e);
                }
            }
        } catch (Exception e) {
            log.warn("MSG_CHAINING 解码失败（忽略）：{}", e.toString());
        }
    }

    private void notifyDuelEnd(DuelResult duelResult) {
        for (DuelListener l : listeners) {
            try {
                l.onDuelEnd(duelResult);
            } catch (Exception e) {
                log.warn("监听器 onDuelEnd 异常", e);
            }
        }
    }

    private void notifyError(Throwable t) {
        log.warn("协议层错误：{}", t.toString());
        for (DuelListener l : listeners) {
            try {
                l.onError(t);
            } catch (Exception e) {
                log.warn("监听器 onError 异常", e);
            }
        }
    }

    private static String msgName(int msgType) {
        return switch (msgType) {
            case ProtocolIds.MSG_SELECT_IDLECMD -> "SELECT_IDLECMD";
            case ProtocolIds.MSG_SELECT_BATTLECMD -> "SELECT_BATTLECMD";
            case ProtocolIds.MSG_SELECT_CHAIN -> "SELECT_CHAIN";
            case ProtocolIds.MSG_SELECT_CARD -> "SELECT_CARD";
            case ProtocolIds.MSG_SELECT_PLACE -> "SELECT_PLACE";
            default -> "0x" + Integer.toHexString(msgType);
        };
    }

    /** 传输层回调 → 会话。 */
    private static final class TransportListener implements Transport.Listener {
        private final DuelSession session;

        TransportListener(DuelSession session) {
            this.session = session;
        }

        @Override
        public void onBytes(byte[] data, int length) {
            session.onBytes(data, length);
        }

        @Override
        public void onClosed() {
            session.log.info("连接被对端关闭");
            session.stage = Stage.CLOSED;
            for (DuelListener l : session.listeners) {
                l.onDisconnected();
            }
        }

        @Override
        public void onError(Throwable t) {
            session.notifyError(t);
        }
    }

    /** 镜像视图桥（请求解码用）。 */
    private final class MirrorViewImpl implements RequestDecoder.MirrorView {
        @Override
        public int selfSeat() {
            return mySeat;
        }

        @Override
        public ZoneRef firstEmptyZone(
                ZoneKind kind) {
            if (projector == null) {
                return null;
            }
            int location;
            int slots;
            switch (kind) {
                case MAIN_MONSTER_ZONE -> {
                    location = ZoneMapper.LOC_MZONE;
                    slots = 5;
                }
                case SPELL_TRAP_ZONE -> {
                    location = ZoneMapper.LOC_SZONE;
                    slots = 5;
                }
                case FIELD_ZONE -> {
                    location = ZoneMapper.LOC_FZONE;
                    slots = 1;
                }
                default -> {
                    return null;
                }
            }
            for (int seq = 0; seq < slots; seq++) {
                if (projector.instanceIdAt(mySeat, location, seq) == 0) {
                    return ZoneMapper.toZone(mySeat, location, seq,
                            PlayerSide.SELF);
                }
            }
            return null;
        }

        @Override
        public ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code) {
            CardIdentityResolver resolver = new CardIdentityResolver();
            var identity = resolver.of(code);
            long knownInstance = projector.instanceIdAt(absController, location, sequence);
            if (code == 0 && knownInstance > 0) {
                // 协议对部分卡发 code=0（对手场上的卡 / 里侧卡），但投影层早就知道它是什么。
                // 不回填的话候选全是"未知卡"，模型只能靠槽位猜——实战里它把「超魔导龙骑士」
                // 选成破坏目标（该卡『不会被效果破坏』），整次除去直接白费。
                var projected = projector.identityAt(absController, location, sequence);
                if (!projected.isUnknown()) {
                    identity = projected;
                }
            }
            var zone = ZoneMapper.toZone(absController, location, sequence,
                    absController == mySeat ? PlayerSide.SELF
                            : PlayerSide.OPPONENT);
            long instanceId = projector.instanceIdAt(absController, location, sequence);
            if (zone == null) {
                zone = ZoneRef.deck(
                        absController == mySeat ? PlayerSide.SELF
                                : PlayerSide.OPPONENT);
            } else if (sequence > 0) {
                // 列表区域（卡组/额外卡组/墓地/除外/手牌）保留真实序号：
                // 否则同名牌候选（如三张青眼白龙）解码成完全相等的记录，无法区分。
                zone = switch (zone.kind()) {
                    case DECK, EXTRA_DECK, GRAVEYARD, BANISHED, HAND ->
                            new ZoneRef(zone.kind(), zone.owner(), sequence);
                    default -> zone;
                };
            }
            return new ChoiceRequest.CardChoice(instanceId, identity, zone);
        }
    }

    /** 身份工厂（把 resolver 转成 decode 所需形态）。 */
    private final class CardIdentityResolver {
        CardIdentity of(long code) {
            if (code == 0) {
                return CardIdentity.UNKNOWN;
            }
            var definition = config.cardResolver() == null
                    ? null
                    : config.cardResolver().resolve(code).orElse(null);
            return new CardIdentity.Known(code, definition);
        }
    }
}
