package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelRecordFeed;
import com.github.coldgust.ygoagent.llm.LlmChatException;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.llm.service.PlannerAgent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorKeeper;
import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 战术幕僚的持续 session 门面（docs/planner-judge-memory-redesign.md §6）：只做编排，不持有可拆状态。
 * <ul>
 *   <li>连续对话：整局一份 {@link DuelChatMemory}（pinned [system, 静态资料] + 决斗记录追加流 +
 *       历次建议），修剪由 memory 承载；</li>
 *   <li>锚点：回合切换时记录流拉取（入史）+ 局面快照 stage（不入史）+ 薄请求行，请求本回合建议
 *       （{@link AdvisorKeeper} 持有最新快照）；</li>
 *   <li>修订：MAIN/BATTLE flush 触发、配额内（每回合一次），同构锚点（ADVISOR_REVISION_TEMPLATE）；</li>
 *   <li>keep：建议仍适用 → 快照原样保留，不 carry-over（渲染层按回合号标"仅供参考"）；</li>
 *   <li>降级：LLM 失败进入冷却期，期间请求快速返回空（决策器靠记忆与快照自行判断）。</li>
 * </ul>
 * 全部 LLM 调用在单线程后台执行器上运行；对外状态为不可变快照（无锁读）。
 * 抢答与重规划已随顾问定位定稿删除（规划器不再回答候选）。
 */
public final class PlannerSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlannerSession.class);

    /** 修订 checkpoint 的每回合上限：顾问建议是回合级主线，多次修订大多回 keep 白烧配额。 */
    private static final int REVISION_CHECKPOINTS_PER_TURN = 1;

    private final PlannerAgent plannerAgent;
    private final ContextPackBuilder packer;
    private final DuelChatMemory memory;
    private final DuelRecordFeed feed;
    private final PipelineSettings settings;
    private final DecisionTraceWriter traceWriter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ygo-planner");
        t.setDaemon(true);
        return t;
    });

    private final AdvisorKeeper keeper = new AdvisorKeeper();
    private final EventCheckpoints checkpoints = new EventCheckpoints();
    private final AtomicReference<Instant> degradedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong checkpointCount = new AtomicLong();
    /** 本回合是否已因"keep 但无建议"补过一次（防每轮都补，刷爆配额）。 */
    private final Set<String> keepWithoutAdviceRetried = ConcurrentHashMap.newKeySet();
    private final VerboseLog trace;

    /** 当前回合键（"回合数:回合方"）：锚点触发、每回合配额与恢复的键。 */
    private String turnKey = "";

    /**
     * 可选依赖用 Builder 逐项声明（望远镜构造器在 ContextPackBuilder 有失控教训）。
     */
    public static Builder builder(PlannerAgent plannerAgent, ContextPackBuilder packer,
                                  PipelineSettings settings, DuelChatMemory memory) {
        return new Builder(plannerAgent, packer, settings, memory);
    }

    /** 依赖收集器：必填四件套 + 可选轨迹/日志（缺省走静默默认）。 */
    public static final class Builder {
        private final PlannerAgent plannerAgent;
        private final ContextPackBuilder packer;
        private final PipelineSettings settings;
        private final DuelChatMemory memory;
        private DecisionTraceWriter traceWriter;
        private DuelRecordFeed feed;
        private VerboseLog trace;

        private Builder(PlannerAgent plannerAgent, ContextPackBuilder packer,
                        PipelineSettings settings, DuelChatMemory memory) {
            this.plannerAgent = plannerAgent;
            this.packer = packer;
            this.settings = settings;
            this.memory = memory;
        }

        /** 决策轨迹落点（缺省 NOOP）。 */
        public Builder traceWriter(DecisionTraceWriter value) {
            this.traceWriter = value;
            return this;
        }

        /** 决斗记录流写入方（必填：锚点/修订前用它拉记录入史）。 */
        public Builder feed(DuelRecordFeed value) {
            this.feed = value;
            return this;
        }

        /** 全链路日志（缺省全关）。 */
        public Builder trace(VerboseLog value) {
            this.trace = value;
            return this;
        }

        public PlannerSession build() {
            if (feed == null) {
                throw new IllegalStateException("PlannerSession 缺 feed（记录流拉取的必填接线）");
            }
            return new PlannerSession(plannerAgent, packer, settings, memory,
                    traceWriter, feed, trace);
        }
    }

    private PlannerSession(PlannerAgent plannerAgent, ContextPackBuilder packer,
                           PipelineSettings settings, DuelChatMemory memory,
                           DecisionTraceWriter traceWriter, DuelRecordFeed feed, VerboseLog trace) {
        this.plannerAgent = plannerAgent;
        this.packer = packer;
        this.settings = settings;
        this.memory = memory;
        this.feed = feed;
        this.trace = VerboseLog.offOr(trace);
        this.traceWriter = DecisionTraceWriter.noopOr(traceWriter);
    }

    private void adviceEvent(String kind, Map<String, Object> payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("adviceKind", kind);
            traceWriter.event("ADVICE_EVENT", event);
        } catch (RuntimeException ignored) {
            // 轨迹写入不得影响规划
        }
    }

    public void observePoint(DecisionPoint point) {
        String key = point.turn() + ":" + point.state().turnPlayer();
        if (!key.equals(turnKey)) {
            worker.execute(() -> beginTurnAnchor(key, point));
        }
    }

    /**
     * ④ 决策器的建议来源：最新建议快照（渲染层按回合号标 stale）。
     */
    public Optional<AdvisorKeeper.Snapshot> currentAdvice() {
        return keeper.current();
    }

    /**
     * 决策时等待规划器：本回合首次决策等锚点建议发布（预算内轮询；keep 语义下快照可能仍是
     * 上回合的——渲染层标 stale）。不健康/超时 → empty（调用方落 ② 直答）。
     */
    public Optional<AdvisorKeeper.Snapshot> waitForAdvice(long waitMs) {
        if (waitMs <= 0 || degraded()) {
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        long deadline = start + waitMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                Optional<AdvisorKeeper.Snapshot> advice = keeper.current();
                if (advice.isPresent()) {
                    return advice;
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            // 等待也要计入"本回合为等规划器而推迟应答"的额度：首动作多等之后，
            // 后续 ④ 若仍按满额累加，一回合的应答时间会被悄悄花光。
            checkpoints.addTurnBudget(System.currentTimeMillis() - start);
        }
        return Optional.empty();
    }

    /** 本回合"等规划器"的剩余额度（预算由 waitForAdvice 累计）。 */
    public long remainingTurnBudget() {
        return Math.max(0, settings.waitPerTurnMs() - checkpoints.turnBudgetUsedMs());
    }

    public boolean healthy() {
        return !degraded();
    }

    public void drainForTests() {
        try {
            worker.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("drain failed", e);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    /**
     * 回合切换：重置回合级计数；对话记忆连续不清——跨回合上下文由
     * DuelChatMemory（pinned 前缀 + 追加记录流 + 历次建议）承载。
     */
    private void beginTurnAnchor(String key, DecisionPoint point) {
        turnKey = key;
        checkpoints.resetForTurn();
        checkpointCount.set(0);
        checkpoints.resetTurnBudget();
        keepWithoutAdviceRetried.clear();
        long startedAt = System.currentTimeMillis();
        // 记录流先入史（回合收尾摘要 + 增量），快照随后 stage——厚内容不入记忆
        feed.fillBeforeCall(memory, point.turn());
        try {
            memory.stageOnce(UserMessage.from(packer.plannerSnapshot(point.state())));
            AdvisorAnswer answer;
            try (var ignored = trace.scope("规划器 anchor")) {
                answer = plannerAgent.checkpoint(anchorRequest(point));
            }
            completeCheckpoint("anchor", answer, startedAt, point);
        } catch (RuntimeException e) {
            handleCheckpointFailure("anchor", e);
        } finally {
            memory.dropEphemeral();
        }
    }

    /** 锚点请求行（A.2）；无可沿用建议时明确禁止 keep（防"keep 无建议"空转一轮）。 */
    private String anchorRequest(DecisionPoint point) {
        int turn = point.turn();
        boolean oppTurn = !point.state().isMyTurn();
        String side = point.state().isMyTurn() ? "我方回合" : "对方回合";
        String request = oppTurn
                ? PlannerPrompts.ADVISOR_ANCHOR_OPP_TEMPLATE.formatted(turn)
                : PlannerPrompts.ADVISOR_ANCHOR_TEMPLATE.formatted(turn, side);
        if (keeper.isEmpty()) {
            request = request
                    + "\n注意：这是本回合首次请求，当前没有任何可沿用的建议"
                    + "——禁止回复 {\"keep\": true}，请直接输出完整的建议 JSON。";
        }
        return request;
    }

    /**
     * 修订 checkpoint（MAIN/BATTLE flush 触发）：配额内把"记录已追加 + 最新快照"带给幕僚，
     * 请求 keep 或更新建议。
     */
    public void requestRevision(DecisionPoint point) {
        if (turnKey.isEmpty() || degraded()) {
            return;
        }
        if (!checkpoints.quotaAvailable(REVISION_CHECKPOINTS_PER_TURN,
                checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
            return; // 成本闸：本回合不再修订
        }
        if (!checkpoints.tryQueue()) {
            return; // 已有在途检查点
        }
        String segment = turnKey;
        worker.execute(() -> {
            try {
                if (!segment.equals(turnKey)) {
                    return; // 段已轮换：这是上一回合的事，新回合的锚点会重新规划
                }
                if (!checkpoints.quotaAvailable(REVISION_CHECKPOINTS_PER_TURN,
                        checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
                    return;
                }
                checkpoints.incrementSent();
                long startedAt = System.currentTimeMillis();
                feed.fillBeforeCall(memory, point.turn());
                try {
                    memory.stageOnce(UserMessage.from(packer.plannerSnapshot(point.state())));
                    AdvisorAnswer answer;
                    try (var ignored = trace.scope("规划器 revision")) {
                        answer = plannerAgent.checkpoint(PlannerPrompts.ADVISOR_REVISION_TEMPLATE);
                    }
                    completeCheckpoint("revision", answer, startedAt, point);
                } catch (RuntimeException e) {
                    // 与锚点同构：调用失败进冷却、解析失败保留建议；漏 catch 会打死 worker 线程（实测事故）
                    handleCheckpointFailure("revision", e);
                } finally {
                    memory.dropEphemeral();
                }
            } finally {
                checkpoints.release();
            }
        });
    }

    /**
     * checkpoint 后处理（keep 判定 / 建议解析 / 发布）。调用失败与解析失败分开处理：
     * 解析失败（框架 POJO 解析抛出的非 LlmChatException）不是"规划器坏了"，保留当前建议。
     */
    private void completeCheckpoint(String kind, AdvisorAnswer raw, long startedAt, DecisionPoint point) {
        long elapsed = System.currentTimeMillis() - startedAt;
        checkpointCount.incrementAndGet();
        AdvisorAnswer answer = raw;
        if (Boolean.TRUE.equals(answer.keep())) {
            if (keeper.isEmpty()) {
                log.info("规划器回 keep 但当前没有建议（kind={}），改请求重出建议", kind);
                adviceEvent("ADVICE_KEEP_WITHOUT_ADVICE", Map.of("checkpoint", String.valueOf(kind)));
                if (keepWithoutAdviceRetried.add(turnKey)) {
                    runKeepRecovery(kind, point);
                }
                return;
            }
            log.info("规划器回 keep（{}）：建议沿用（adviceId={}）", kind,
                    keeper.current().map(AdvisorKeeper.Snapshot::adviceId).orElse("?"));
            adviceEvent("ADVICE_KEPT", Map.of(
                    "checkpoint", String.valueOf(kind),
                    "adviceId", keeper.current().map(AdvisorKeeper.Snapshot::adviceId).orElse("")));
            return;
        }
        if (!answer.hasAdvice()) {
            String reason = "输出无效：keep 缺失且建议内容为空";
            log.warn("规划器输出无法解析为建议（kind={}，原因：{}）", kind, reason);
            adviceEvent("PARSE_FAILED", Map.of("checkpoint", String.valueOf(kind),
                    "reason", reason));
            return;
        }
        String adviceId = "A" + turnKey + "#" + checkpointCount.get();
        keeper.publish(answer, adviceId, point.turn());
        // 建议台账：发布的建议原文（策略/目标/战术/情报）作为 RECORD 行进记忆，永不修剪——
        // 写新建议前看得到上一份原文，前后矛盾在模型自己的历史里就有对照（2026-11-14 用户指令）
        memory.appendRecord(adviceLedger(point.turn(), adviceId, answer));
        log.info("规划器产出建议 {}（{}ms，confidence={}）", adviceId, elapsed,
                String.format(Locale.ROOT, "%.2f", answer.confidenceOr(0.5)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("adviceId", adviceId);
        payload.put("turn", point.turn());
        payload.put("checkpoint", String.valueOf(kind));
        payload.put("elapsedMs", elapsed);
        payload.put("confidence", answer.confidenceOr(0.5));
        payload.put("strategy", String.valueOf(answer.strategy()));
        payload.put("analysis", String.valueOf(answer.analysis()));
        payload.put("resources", String.valueOf(answer.resources()));
        payload.put("goal", String.valueOf(answer.goal()));
        payload.put("options", answer.options() == null ? List.of()
                : answer.options().stream().map(o -> Map.<String, Object>of(
                        "line", o.line() == null ? List.of() : o.line(),
                        "gain", String.valueOf(o.gain()),
                        "risk", String.valueOf(o.risk()),
                        "pick", Boolean.TRUE.equals(o.pick()),
                        "why", String.valueOf(o.why()))).toList());
        payload.put("tactics", String.valueOf(answer.tactics()));
        payload.put("intel", String.valueOf(answer.intel()));
        payload.put("combo", String.valueOf(answer.combo()));
        adviceEvent("ADVICE_PUBLISHED", payload);
    }

    /** 建议台账行：strategy/goal/tactics/intel 的持久化摘要（RECORD，永不修剪）。 */
    private static String adviceLedger(int turn, String adviceId, AdvisorAnswer a) {
        StringBuilder sb = new StringBuilder("〔历次建议 T").append(turn).append(" · ").append(adviceId).append("〕");
        if (!Texts.isBlank(a.strategy())) {
            sb.append("策略：").append(Texts.flattenClip(a.strategy(), 160)).append(" ｜ ");
        }
        if (!Texts.isBlank(a.goal())) {
            sb.append("目标：").append(Texts.flattenClip(a.goal(), 80)).append(" ｜ ");
        }
        if (!Texts.isBlank(a.tactics())) {
            sb.append("战术：").append(Texts.flattenClip(a.tactics(), 160)).append(" ｜ ");
        }
        if (!Texts.isBlank(a.intel())) {
            sb.append("情报：").append(Texts.flattenClip(a.intel(), 160));
        }
        return sb.toString();
    }

    /** keep 恢复：同一次调用现场（快照原样 stage）请求完整建议，每回合至多一次。 */
    private void runKeepRecovery(String kind, DecisionPoint point) {
        long startedAt = System.currentTimeMillis();
        try {
            memory.stageOnce(UserMessage.from(packer.plannerSnapshot(point.state())));
            AdvisorAnswer answer;
            try (var ignored = trace.scope("规划器 keep 恢复")) {
                answer = plannerAgent.checkpoint(
                        PlannerPrompts.ADVISOR_KEEP_RECOVERY_TEMPLATE.formatted(String.valueOf(kind)));
            }
            completeCheckpoint("recovery", answer, startedAt, point);
        } catch (RuntimeException e) {
            handleCheckpointFailure("recovery", e);
        } finally {
            memory.dropEphemeral();
        }
    }

    private void handleCheckpointFailure(String kind, RuntimeException e) {
        if (isCallFailure(e)) {
            log.warn("规划器 checkpoint 失败（kind={}）: {}", kind, e.toString());
            adviceEvent("CHECKPOINT_FAILED", Map.of("checkpoint", String.valueOf(kind)));
            degrade();
        } else {
            // 输出不可解析：模型行为问题，不是调用链故障——保留当前建议，不进冷却
            log.warn("规划器输出不可解析（kind={}）: {}", kind, e.toString());
            adviceEvent("PARSE_FAILED", Map.of("checkpoint", String.valueOf(kind),
                    "reason", "输出不可解析: " + e.getClass().getSimpleName()));
        }
    }

    /** 调用链故障（HTTP/超时/网络）的判定：异常链上找得到 {@link LlmChatException}。 */
    static boolean isCallFailure(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof LlmChatException) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    private boolean degraded() {
        return Instant.now().isBefore(degradedUntil.get());
    }

    private void degrade() {
        degradedUntil.set(Instant.now().plusMillis(settings.plannerCooldownMs()));
    }
}
