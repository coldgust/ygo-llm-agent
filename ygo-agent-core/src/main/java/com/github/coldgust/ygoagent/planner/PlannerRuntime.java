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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 战术幕僚的持续运行时门面（docs/planner-judge-memory-redesign.md §6）：只做编排，不持有可拆状态。
 * 名字取 Runtime 而非 Session，是因为它承载的不是"一次会话"，而是幕僚跑起来所需的全部机制——
 * 整局记忆、后台执行线程、锚点与配额、降级冷却；会话本体是里面的 {@link DuelChatMemory}。
 * <ul>
 *   <li>连续对话：整局一份 {@link DuelChatMemory}（pinned [system, 静态资料] + 决斗记录追加流 +
 *       历次建议），修剪由 memory 承载；</li>
 *   <li>锚点：回合切换时记录流拉取（入史）+ 局面快照 stage（不入史）+ 薄请求行，请求本回合建议
 *       （{@link AdvisorKeeper} 持有最新快照）。<b>每回合规划一次</b>：回合内不再主动修订，
 *       keep 也随之废止——每次 checkpoint 都必须产出全量建议（2026-11-14 用户指令）；</li>
 *   <li>重规划：决策器判决 needReplan=true 触发（每回合一次配额），请求行原样带决策器给出的
 *       重规划原因（ADVISOR_REPLAN_TEMPLATE）；发起即作废旧建议（{@link AdvisorKeeper#retire}）——
 *       新建议没来得及发布前，决策器不带旧规划；</li>
 *   <li>降级：LLM 失败进入冷却期，期间请求快速返回空（决策器靠记忆与快照自行判断）。</li>
 * </ul>
 * 全部 LLM 调用在单线程后台执行器上运行；对外状态为不可变快照（无锁读）。
 * 抢答已随顾问定位定稿删除（规划器不再回答候选）；回合内唯一的重规划由决策器判决驱动。
 */
public final class PlannerRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlannerRuntime.class);

    /** 重规划 checkpoint 的每回合上限：唯一回合内规划；封顶防"决策器每手都判重规划"刷爆配额。 */
    private static final int REPLAN_CHECKPOINTS_PER_TURN = 1;

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
    private final VerboseLog trace;

    /** 当前回合键（"回合数:回合方"）：锚点触发、每回合配额与恢复的键。volatile：决策线程读、worker 写。 */
    private volatile String turnKey = "";

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

        public PlannerRuntime build() {
            if (feed == null) {
                throw new IllegalStateException("PlannerRuntime 缺 feed（记录流拉取的必填接线）");
            }
            return new PlannerRuntime(plannerAgent, packer, settings, memory,
                    traceWriter, feed, trace);
        }
    }

    private PlannerRuntime(PlannerAgent plannerAgent, ContextPackBuilder packer,
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
     * 决策时等待规划器：本回合首次决策等锚点建议发布（预算内轮询）。
     * 上回合锚点失败时快照可能仍是上回合的——渲染层按回合号标 stale。
     * 不健康/超时 → empty（调用方落 ② 判断）。
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

    /** 锚点请求行（A.2）：每回合规划一次、只此一次，必须产出全量建议（keep 已废止）。 */
    private String anchorRequest(DecisionPoint point) {
        int turn = point.turn();
        boolean oppTurn = !point.state().isMyTurn();
        String side = point.state().isMyTurn() ? "我方回合" : "对方回合";
        return oppTurn
                ? PlannerPrompts.ADVISOR_ANCHOR_OPP_TEMPLATE.formatted(turn)
                : PlannerPrompts.ADVISOR_ANCHOR_TEMPLATE.formatted(turn, side);
    }

    /**
     * 重规划（决策器判决 needReplan=true 触发，唯一的回合内规划）：
     * <ol>
     *   <li>配额/在途闸通过后<b>立即作废旧建议</b>（{@link AdvisorKeeper#retire}）——
     *       决策器已判定它不可用，新规划发布前不得再带给决策器；</li>
     *   <li>worker 上同构锚点跑重规划 checkpoint：请求行原样转达决策器给出的原因，
     *       发布走 completeCheckpoint。</li>
     * </ol>
     * 调用方（决策线程）随即重置"本回合已等过建议"标记（TurnMemory.clearWaitedForAdvice），
     * 下一次决策前按配置的首次等待时长（firstAdviceWaitMs）再等一次新规划。
     *
     * @param reason 决策器给出的重规划原因（可空；空白时请求行给占位说明）
     */
    public void requestReplan(DecisionPoint point, String reason) {
        if (turnKey.isEmpty() || degraded()) {
            return;
        }
        if (!checkpoints.quotaAvailable(REPLAN_CHECKPOINTS_PER_TURN,
                checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
            return; // 成本闸：本回合不再重规划（沿用上一份建议或无建议判断）
        }
        if (!checkpoints.tryQueue()) {
            return; // 已有在途检查点（锚点或上一次重规划）：规划已在路上，不再重复作废
        }
        keeper.retire();
        log.info("决策器判决重规划（回合 {}）：旧建议已作废，原因：{}",
                point.turn(), Texts.isBlank(reason) ? "（未给出）" : Texts.flattenClip(reason, 120));
        adviceEvent("ADVICE_REPLAN_REQUESTED", Map.of(
                "turn", point.turn(),
                "reason", String.valueOf(Texts.isBlank(reason) ? "" : Texts.flattenClip(reason, 200))));
        String segment = turnKey;
        worker.execute(() -> {
            try {
                if (!segment.equals(turnKey)) {
                    return; // 段已轮换：这是上一回合的事，新回合的锚点会重新规划
                }
                if (!checkpoints.quotaAvailable(REPLAN_CHECKPOINTS_PER_TURN,
                        checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
                    return;
                }
                checkpoints.incrementSent();
                long startedAt = System.currentTimeMillis();
                feed.fillBeforeCall(memory, point.turn());
                try {
                    memory.stageOnce(UserMessage.from(packer.plannerSnapshot(point.state())));
                    AdvisorAnswer answer;
                    try (var ignored = trace.scope("规划器 replan")) {
                        answer = plannerAgent.checkpoint(replanRequest(reason));
                    }
                    completeCheckpoint("replan", answer, startedAt, point);
                } catch (RuntimeException e) {
                    // 与锚点同构：调用失败进冷却、解析失败保留建议；漏 catch 会打死 worker 线程（实测事故）
                    handleCheckpointFailure("replan", e);
                } finally {
                    memory.dropEphemeral();
                }
            } finally {
                checkpoints.release();
            }
        });
    }

    /** 重规划请求行（A.2 同构）：决策器的原因原样转达（截断防护），空白给占位说明。 */
    private static String replanRequest(String reason) {
        String why = Texts.isBlank(reason) ? "（决策器未给出原因）" : Texts.flattenClip(reason, 200);
        return PlannerPrompts.ADVISOR_REPLAN_TEMPLATE.formatted(why);
    }

    /**
     * checkpoint 后处理（解析校验 / 发布）。调用失败与解析失败分开处理：
     * 解析失败（框架 POJO 解析抛出的非 LlmChatException）不是"规划器坏了"，只记因不发布。
     * keep 已废止（2026-11-14）：每次 checkpoint 都必须产出全量建议——
     * 内容为空即视为未按 schema 输出。
     */
    private void completeCheckpoint(String kind, AdvisorAnswer raw, long startedAt, DecisionPoint point) {
        long elapsed = System.currentTimeMillis() - startedAt;
        checkpointCount.incrementAndGet();
        AdvisorAnswer answer = raw;
        if (!answer.hasAdvice()) {
            String reason = "输出无效：无实质建议内容";
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
