package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.map.SelectionDescriber;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;
import com.github.coldgust.ygoagent.decision.planexec.CardRagFactService;
import com.github.coldgust.ygoagent.decision.planexec.ContextPackBuilder;
import com.github.coldgust.ygoagent.decision.planexec.DecisionTools;
import com.github.coldgust.ygoagent.decision.planexec.DuelMathService;
import com.github.coldgust.ygoagent.decision.planexec.PlanExecutorDecisionPort;
import com.github.coldgust.ygoagent.decision.planexec.PlannerSettings;
import com.github.coldgust.ygoagent.decision.planexec.llm.CardTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.ComboTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.DuelRecallTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTrace;
import com.github.coldgust.ygoagent.decision.planexec.llm.OpenAiCompatChat;
import com.github.coldgust.ygoagent.decision.planexec.llm.RecordedComboSource;
import com.github.coldgust.ygoagent.decision.planexec.llm.RulebookTool;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlannerSession;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.trace.CompositeTraceWriter;
import com.github.coldgust.ygoagent.decision.trace.TraceLogWriter;
import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygoprosdk.deck.YdkParser;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import com.github.coldgust.ygoprosdk.session.SessionConfig;
import com.github.coldgust.ygoagent.decision.arbiter.SimpleArbiter;
import com.github.coldgust.ygoagent.decision.engine.DefaultDecisionPort;
import com.github.coldgust.ygoagent.decision.port.DecisionPort;
import com.github.coldgust.ygoagent.decision.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.decision.tactical.TacticalPlannerImpl;
import com.github.coldgust.ygoagent.decision.trace.JsonlDecisionTraceWriter;
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

/**
 * 一局的完整执行：连房间 → 上传卡组 → 就绪 → 决策循环 → 统计。
 * <p>CLI（{@link DuelRunnerMain}）与 HTTP 服务（ygo-play-server）共用。
 */
public final class DuelRunner {

    private static final Logger log = LoggerFactory.getLogger(DuelRunner.class);

    private final PlannerSettings planExecSettings;
    private final DuelWebHooks webHooks;
    private final DuelListener extraListener;
    private final CardFacts cardFactsOverride;

    /**
     * @param planExecSettings v6 Planner–Executor 配置；null = 走原快路径（Reflex/Tactical）
     */
    public DuelRunner(PlannerSettings planExecSettings) {
        this(planExecSettings, null);
    }

    public DuelRunner(PlannerSettings planExecSettings,
                      DuelWebHooks webHooks) {
        this(planExecSettings, webHooks, null);
    }

    /**
     * @param extraListener 附加观察者（如实况战场播报）；只读局面，不参与决策
     */
    public DuelRunner(PlannerSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener) {
        this(planExecSettings, webHooks, extraListener, null);
    }

    /**
     * @param cardFactsOverride 卡牌事实来源（可空）。服务端传 PG 实现；CLI 传 null 用本地 jsonl。
     */
    public DuelRunner(PlannerSettings planExecSettings,
                      DuelWebHooks webHooks,
                      DuelListener extraListener,
                      CardFacts cardFactsOverride) {
        this.planExecSettings = planExecSettings;
        this.webHooks = webHooks == null ? DuelWebHooks.none() : webHooks;
        this.extraListener = extraListener;
        this.cardFactsOverride = cardFactsOverride;
    }

    public DuelRunResult run(DuelRunSpec spec, AtomicReference<DuelSession> sessionRef,
                             BooleanSupplier stopRequested) throws Exception {
        if (stopRequested == null) {
            stopRequested = () -> false;
        }
        Deck deck = parseDeck(spec);
        String rawFrames = System.getenv("DUEL_RAW_FRAMES");
        SessionConfig sessionConfig = SessionConfig.builder(spec.host(), spec.name())
                .port(spec.port())
                .deck(deck)
                .firstPlayer("first".equalsIgnoreCase(spec.move())
                        ? SessionConfig.FirstMove.FIRST : SessionConfig.FirstMove.SECOND)
                .answerDelayMillis(0)
                // 看门狗：某个决策点若因为解码/映射异常没有拿到应答，超时后由 SDK 提交保底应答。
                // 不设这个值就是"永久卡住"（实机已出现过：位置请求 0 候选 → 应答非法 → 对局冻结）。
                .choiceTimeout(Duration.ofMillis(choiceTimeoutMillis()))
                .duelLog(spec.duelLogPath())
                .duelLogDecisionContext(true)
                // DUEL_RAW_FRAMES=1：把原始协议帧一并写进对局记录（排查解码用，体积大）
                .duelLogRawFrames(rawFrames != null && !rawFrames.isBlank() && !"0".equals(rawFrames))
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

                if (!waitForDuelStart(session, stopRequested)) {
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
                if (LlmTrace.enabled()) {
                    sinks.add(TraceLogWriter.INSTANCE);
                    log.info("全链路日志已开启（YGO_VERBOSE_LOG）：LLM 请求/响应、工具调用、决策流水都会打到日志");
                }
                DecisionTraceWriter traceWriter =
                        sinks.size() == 1 ? sinks.getFirst()
                                : new CompositeTraceWriter(sinks);
                PlanExecutorDecisionPort planExecPort = null;
                CardFacts cardFacts = null;
                try {
                    DefaultDecisionPort basePort = new DefaultDecisionPort(
                            new ReflexPolicyImpl(),
                            new TacticalPlannerImpl(),
                            new SimpleArbiter(),
                            traceWriter);
                    DecisionPort decisionPort;
                    // combo 录入模式不接 LLM：决策只留给用户回合期间的连锁询问兜底（Reflex，零思考），
                    // 我方回合开始前就已认输，planner/one-shot 没有任何出场机会。
                    if (planExecSettings != null && planExecSettings.enabled() && !spec.comboRecord()) {
                        // v6 Planner–Executor：① Reflex → ② 计划精确匹配 → ③ 相似匹配 → ④ 投机双路 → ⑤ basePort
                        var facts = cardFactsOverride != null
                                ? cardFactsOverride
                                : CardRagFactService
                                        .load(planExecSettings.cardRagPath());
                        cardFacts = facts;
                        String comboReference = comboReferenceText(webHooks.recordedCombos(), spec.archetype());
                        var packer = new ContextPackBuilder(
                                facts, DuelMathService.instance(),
                                spec.deckStrategy(),
                                ContextPackBuilder.RULE_DIGEST,
                                spec.archetype(),
                                !planExecSettings.pathPlanning(),
                                // 己方卡组全清单：规划器据此判断能检索/特召什么，不必现查
                                deckPasscodes(deck),
                                comboReference);
                        if (packer.hasDeckStrategy()) {
                            log.info("已注入卡组思路（{} 字），将作为规划器与决策器判断（④）的重点参考",
                                    spec.deckStrategy().length());
                        }
                        if (packer.hasComboReference()) {
                            log.info("已注入实战连招参考（主题 {}，{} 字）：PG combo 库最近的真实录入",
                                    spec.archetype(), comboReference.length());
                        }
                        if (packer.hasRuleDigest()) {
                            log.info("已预置规则速查（浓缩版）；精确条文可由模型自主调用 search_rulebook 检索");
                        }
                        // 规则检索工具：LightRAG 不可达时工具调用会失败并回填错误，模型仍可基于速查继续
                        var rulebook = RulebookTool.fromEnv();
                        var chat = new OpenAiCompatChat(
                                planExecSettings.baseUrl(), planExecSettings.apiKey(), planExecSettings.model());
                        // 连招查询工具：按主题/关键卡查 PG 实战录入（主要用于推断对手卡组后参考）
                        var comboTool = webHooks.recordedCombos() != null
                                && webHooks.recordedCombos().available()
                                ? new ComboTool(
                                        webHooks.recordedCombos(), spec.archetype())
                                : null;
                        // 查卡工具：规划器也要有——判断"这张卡和那张能不能配合"必须有卡面原文，
                        // 只给卡名等于让它凭训练记忆猜效果（实测就会退化成逐张单卡评价）。
                        var cardTool = new CardTool(facts);
                        // 规划器的工具集：规则 + 查卡 + 连招（不含历史对局检索，那是执行器的事）
                        var plannerTools = new DecisionTools(
                                rulebook, comboTool, cardTool);
                        var planner = new PlannerSession(
                                chat, packer, facts, planExecSettings, traceWriter, plannerTools);
                        var duelRecall = DuelRecallTool.fromEnv();
                        var decisionTools = new DecisionTools(
                                rulebook, comboTool, cardTool, duelRecall);
                        planExecPort = new PlanExecutorDecisionPort(
                                new ReflexPolicyImpl(), planner, packer, chat, planExecSettings, facts,
                                basePort, traceWriter, decisionTools);
                        decisionPort = planExecPort;
                        log.info("v6 Planner–Executor 已启用（model={} wait-decision={}ms wait-turn={}ms 卡库 {}）",
                                planExecSettings.model(), planExecSettings.waitPerDecisionMs(),
                                planExecSettings.waitPerTurnMs(), facts.knownCount());
                    } else {
                        decisionPort = basePort;
                    }
                    RunnerConfig runnerConfig = RunnerConfig.defaults();
                    // 提交内容也要进轨迹/前端：只看"决策路径"看不出到底放了哪个区、发动了哪张卡
                    var factsForNames = cardFacts;
                    var describer = new SelectionDescriber(
                            factsForNames == null ? null : code -> factsForNames.orUnknown(code).name());
                    DuelSessionRunner runner = new DuelSessionRunner(new SdkSessionLink(session), decisionPort,
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
                    return new DuelRunResult(stats, result, spec.tracePath(), spec.duelLogPath());
                } finally {
                    if (planExecPort != null) {
                        planExecPort.close();
                    }
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

    /** 提交事件的载荷：既给人看（summary），也给机器用（seq / 意图）。 */
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

    /** 决策超时（看门狗）：默认 25s，`DUEL_CHOICE_TIMEOUT_MS` 可覆盖；0 表示不启用。 */
    private static long choiceTimeoutMillis() {
        String raw = System.getenv("DUEL_CHOICE_TIMEOUT_MS");
        if (raw == null || raw.isBlank()) {
            return 25_000L;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return 25_000L;
        }
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

    private static boolean waitForDuelStart(DuelSession session, BooleanSupplier stopRequested)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        // 等待对手进房的时长：实机调试常常"先起客户端、再手动开对手"，180s 经常不够
        long limitMs = startWaitMillis();
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

    /** {@code DUEL_START_WAIT_MS}（默认 180s）：等对手进房的上限。 */
    private static long startWaitMillis() {
        String raw = System.getenv("DUEL_START_WAIT_MS");
        if (raw == null || raw.isBlank()) {
            return 180_000;
        }
        try {
            return Math.max(10_000, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return 180_000;
        }
    }

    /**
     * 解析 {@code data/...} 这类相对资源：先按当前工作目录找，找不到就逐级向上找，
     * 直到看到 {@code data} 目录为止（最多 5 层）。
     *
     * <p>为什么需要：{@code mvn spring-boot:run} 把工作目录设成模块目录 {@code ygo-play-server/}，
     * 而数据都在仓库根的 {@code data/} 下。结果就是"卡库能加载、连招库却报不存在"——
     * 实战里表现为 {@code find_combos} 工具静默不注册、规划器拿不到路线提示。
     */
    /**
     * 己方主题的实战连招参考文本：PG combo 库按创建时间倒序取最近 {@code COMBO_CONTEXT_LIMIT}
     * 条（默认 10），渲染成上下文段落。没有主题 / 库为空 / 不可用时返回 null（不注入）。
     */
    private static String comboReferenceText(
            RecordedComboSource recorded,
            String archetype) {
        if (recorded == null || !recorded.available()
                || archetype == null || archetype.isBlank()) {
            return null;
        }
        int limit = comboContextLimit();
        var entries = recorded.recentByArchetype(archetype, limit);
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (var entry : entries) {
            sb.append("#").append(n++).append("（").append(entry.stepCount()).append(" 步）");
            if (!entry.keyCards().isEmpty()) {
                sb.append(" 关键卡牌：").append(String.join("、", entry.keyCards()));
            }
            sb.append("\n");
            if (entry.summary() != null && !entry.summary().isBlank()) {
                sb.append(entry.summary()).append("\n");
            }
            var steps = entry.stepDescriptions();
            if (steps.isEmpty()) {
                continue;
            }
            sb.append("实际操作：");
            for (int i = 0; i < Math.min(6, steps.size()); i++) {
                sb.append(i == 0 ? "" : " → ").append(steps.get(i));
            }
            if (steps.size() > 6) {
                sb.append(" → …（共 ").append(steps.size()).append(" 步）");
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 实战连招注入条数上限：{@code COMBO_CONTEXT_LIMIT}（默认 10）。 */
    private static int comboContextLimit() {
        String raw = System.getenv("COMBO_CONTEXT_LIMIT");
        if (raw == null || raw.isBlank()) {
            return 10;
        }
        try {
            return Math.max(1, Math.min(30, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /** 卡组清单（额外在前：这些是真正会出场的，值得先看到）。 */
    private static List<Long> deckPasscodes(
            Deck deck) {
        List<Long> all = new ArrayList<>(deck.extra());
        all.addAll(deck.main());
        return all;
    }


    /** 主动停止（close 连接）时抛出，由服务层标记 STOPPED。 */
    public static final class DuelStoppedException extends RuntimeException {
        public DuelStoppedException() {
            super("对局已停止");
        }
    }
}
