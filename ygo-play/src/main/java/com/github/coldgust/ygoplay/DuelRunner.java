package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.selection.SelectionDescriber;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.DuelAgent;
import com.github.coldgust.ygoagent.llm.LightRagSettings;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.trace.CompositeTraceWriter;
import com.github.coldgust.ygoagent.trace.TraceLogWriter;
import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygoprosdk.deck.YdkParser;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import com.github.coldgust.ygoprosdk.session.SessionConfig;
import com.github.coldgust.ygoagent.pipeline.DecisionEngine;
import com.github.coldgust.ygoagent.trace.JsonlDecisionTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class DuelRunner {

    private static final Logger log = LoggerFactory.getLogger(DuelRunner.class);

    private final PipelineSettings planExecSettings;
    private final DuelWebHooks webHooks;
    private final DuelListener extraListener;
    private final CardFacts cardFactsOverride;
    /** 规则/历史对局检索的连接配置：由组装根注入（CLI 从 env+dotenv 解析；server 由 Spring 配置绑定）。 */
    private final LightRagSettings lightRag;
    private final VerboseLog verbose;

    public DuelRunner(PipelineSettings planExecSettings) {
        this(planExecSettings, null);
    }

    public DuelRunner(PipelineSettings planExecSettings,
                      DuelWebHooks webHooks) {
        this(planExecSettings, webHooks, null);
    }

    public DuelRunner(PipelineSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener) {
        this(planExecSettings, webHooks, extraListener, null);
    }

    public DuelRunner(PipelineSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener,
                      CardFacts cardFactsOverride) {
        this(planExecSettings, webHooks, extraListener, cardFactsOverride, null);
    }

    public DuelRunner(PipelineSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener,
                      CardFacts cardFactsOverride,
                      LightRagSettings lightRag) {
        this(planExecSettings, webHooks, extraListener, cardFactsOverride, lightRag,
                new VerboseLog(VerboseLogSettings.off()));
    }

    public DuelRunner(PipelineSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener,
                      CardFacts cardFactsOverride,
                      LightRagSettings lightRag,
                      VerboseLog verbose) {
        this.planExecSettings = planExecSettings;
        this.webHooks = webHooks == null ? DuelWebHooks.none() : webHooks;
        this.extraListener = extraListener;
        this.cardFactsOverride = cardFactsOverride;
        this.lightRag = lightRag;
        this.verbose = VerboseLog.offOr(verbose);
    }

    public DuelRunResult run(DuelRunSpec spec, AtomicReference<DuelSession> sessionRef,
                             BooleanSupplier stopRequested) throws Exception {
        if (stopRequested == null) {
            stopRequested = () -> false;
        }
        Deck deck = parseDeck(spec);
        SessionConfig sessionConfig = SessionConfig.builder(spec.host(), spec.name())
                .port(spec.port())
                .deck(deck)
                .firstPlayer("first".equalsIgnoreCase(spec.move())
                        ? SessionConfig.FirstMove.FIRST : SessionConfig.FirstMove.SECOND)
                .answerDelayMillis(0)
                // 看门狗：某个决策点若因为解码/映射异常没有拿到应答，超时后由 SDK 提交保底应答。
                // 不设这个值就是"永久卡住"（实机已出现过：位置请求 0 候选 → 应答非法 → 对局冻结）。
                .choiceTimeout(Duration.ofMillis(spec.tuning().choiceTimeoutMillis()))
                .duelLog(spec.duelLogPath())
                .duelLogDecisionContext(true)
                // DUEL_RAW_FRAMES=1：把原始协议帧一并写进对局记录（排查解码用，体积大）
                .duelLogRawFrames(spec.tuning().duelLogRawFrames())
                .build();

        try (DuelSession session = DuelSession.start(sessionConfig)) {
            sessionRef.set(session);
            if (extraListener != null) {
                session.addListener(extraListener);
            }
            // combo 录入：轮到我方回合立即认输（用户回合的操作才是录入对象）
            ComboRecordSurrender recordSurrender = spec.comboRecord()
                    ? new ComboRecordSurrender(session::surrender) : null;
            if (recordSurrender != null) {
                session.addListener(recordSurrender);
            }
            try {
                if (!waitForSeat(session, stopRequested)) {
                    throw new DuelStoppedException();
                }
                session.uploadDeck(deck);
                session.setReady(true);
                log.info("卡组已上传并就绪（座位 {}），等待开局…", session.selfSeat());

                if (!waitForDuelStart(session, stopRequested, spec.tuning().startWaitMillis())) {
                    throw new DuelStoppedException();
                }
                log.info("对局开始");

                JsonlDecisionTraceWriter jsonlWriter = new JsonlDecisionTraceWriter(spec.tracePath());
                String duelId = stripExtension(spec.duelLogPath().getFileName().toString());
                DuelWebHooks.DuelEventListener listener = webHooks.listener();
                if (listener != null) {
                    try {
                        listener.onEvent(duelId, "DUEL_STATUS", Map.of(
                                "duelId", duelId, "status", "RUNNING",
                                "archetype", String.valueOf(spec.archetype()),
                                "comboRecord", spec.comboRecord()));
                    } catch (RuntimeException ignored) {
                    }
                }
                // 轨迹扇出：JSONL（复盘用）+ Web SSE（前端用）+ 日志（YGO_VERBOSE_LOG 打开时，人直接看）
                List<DecisionTraceWriter> sinks =
                        new ArrayList<>();
                sinks.add(jsonlWriter);
                if (webHooks.extraTraceWriter() != null) {
                    sinks.add(webHooks.extraTraceWriter());
                }
                if (verbose.enabled()) {
                    sinks.add(new TraceLogWriter(verbose));
                    log.info("全链路日志已开启（YGO_VERBOSE_LOG）：LLM 请求/响应、工具调用、决策流水都会打到日志");
                }
                DecisionTraceWriter traceWriter =
                        sinks.size() == 1 ? sinks.getFirst()
                                : new CompositeTraceWriter(sinks);
                // combo 录入模式不接 LLM：决策只留给用户回合期间的连锁询问兜底（Reflex，零思考），
                // 我方回合开始前就已认输，planner/直答 没有任何出场机会。
                PipelineSettings llmSettings = planExecSettings != null && planExecSettings.enabled()
                        && !spec.comboRecord() ? planExecSettings : null;
                // 决斗过程记录：session 内的对局事件流（含对手动作）经适配器供给上下文渲染
                DuelLogTimeline timeline = new DuelLogTimeline(session.duelLog());
                DuelAgent agent = DuelAgent.open(llmSettings, DuelAgent.Config.builder()
                        .facts(cardFactsOverride)
                        .traceWriter(traceWriter)
                        .archetype(spec.archetype())
                        .deckStrategy(spec.deckStrategy())
                        // 己方卡组全清单：规划器据此判断能检索/特召什么，不必现查
                        .deckPasscodes(deckPasscodes(deck))
                        .combos(webHooks.recordedCombos())
                        .comboContextLimit(spec.tuning().comboContextLimit())
                        .lightRag(lightRag)
                        .timeline(timeline)
                        .build(), verbose);
                try {
                    DecisionEngine decisionEngine = agent;
                    RunnerConfig runnerConfig = RunnerConfig.defaults();
                    // 提交内容也要进轨迹/前端：只看"决策路径"看不出到底放了哪个区、发动了哪张卡
                    var factsForNames = agent.cardFacts();
                    var describer = new SelectionDescriber(
                            factsForNames == null ? null : code -> factsForNames.orUnknown(code).name());
                    DuelSessionRunner runner = new DuelSessionRunner(new SdkSessionLink(session), decisionEngine,
                            runnerConfig,
                            submission -> traceWriter.event("SUBMITTED", submissionPayload(submission)),
                            describer);
                    RunStats stats = runner.run();
                    // 录入对局已按计划认输结束：同步落库（提取步骤 → LLM 摘要 → PG → SSE）。
                    // 放在对局线程上做，保证 COMBO_RECORDED 事件先于终态 DUEL_STATUS 到达前端。
                    if (recordSurrender != null && recordSurrender.surrendered()
                            && webHooks.comboRecordSink() != null) {
                        try {
                            webHooks.comboRecordSink().accept(new ComboRecording(
                                    duelId, spec.archetype(), spec.duelLogPath()));
                        } catch (Throwable e) {
                            // 录入是旁路产物：任何失败（含 Error）都不能打断对局结果上报
                            log.warn("combo 录入落库失败（不影响对局结果）: {}", e.toString());
                        }
                    }
                    DuelResult result = session.state().isFinished() ? session.state().result() : null;
                    traceWriter.event("RUN_DONE", Map.of(
                            "answered", stats.answered(), "fallbacks", stats.fallbacks()));
                    return new DuelRunResult(stats, result);
                } finally {
                    agent.close();
                }
            } finally {
                sessionRef.compareAndSet(session, null);
            }
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Map<String, Object> submissionPayload(DuelSessionRunner.Submission s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (s.point() != null) {
            payload.put("seq", s.point().seq());
            payload.put("turn", s.point().turn());
            payload.put("requestKind", s.point().kind().name());
            payload.put("requestLabel", s.point().kind().label());
        }
        if (s.decision() != null) {
            payload.put("levelLabel", s.decision().pathKind().label());
            payload.put("intent", s.decision().intent().getClass().getSimpleName());
        }
        payload.put("submitted", s.summary());
        payload.put("fallback", s.fallback());
        return payload;
    }


    private static Deck parseDeck(DuelRunSpec spec) throws Exception {
        if (spec.deckYdkText() != null && !spec.deckYdkText().isBlank()) {
            return YdkParser.parse(spec.deckYdkText());
        }
        if (spec.deckPath() != null) {
            return YdkParser.parse(Files.readString(spec.deckPath()));
        }
        throw new IllegalArgumentException("缺少卡组：deckYdkText 或 deckPath");
    }

    private static boolean waitForSeat(DuelSession session, BooleanSupplier stopRequested)
            throws InterruptedException, DuelStoppedException {
        long start = System.currentTimeMillis();
        while (!(session.selfSeat() == 0 || session.selfSeat() == 1)) {
            if (stopRequested.getAsBoolean() || session.stage() == DuelSession.Stage.CLOSED) {
                return false;
            }
            if (System.currentTimeMillis() - start > 60_000) {
                log.error("60 秒内未获得决斗位，退出");
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }

    private static boolean waitForDuelStart(DuelSession session, BooleanSupplier stopRequested,
                                            long startWaitMillis)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        long limitMs = startWaitMillis;
        while (!(session.stage() == DuelSession.Stage.DUEL
                && session.state().turnNumber() >= 1
                && !session.state().isFinished())) {
            if (stopRequested.getAsBoolean() || session.stage() == DuelSession.Stage.CLOSED) {
                return false;
            }
            if (System.currentTimeMillis() - start > limitMs) {
                log.warn("{} 秒内未开局，退出", limitMs / 1000);
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }




    private static List<Long> deckPasscodes(
            Deck deck) {
        List<Long> all = new ArrayList<>(deck.extra());
        all.addAll(deck.main());
        return all;
    }


    public static final class DuelStoppedException extends RuntimeException {
        public DuelStoppedException() {
            super("对局已停止");
        }
    }
}
