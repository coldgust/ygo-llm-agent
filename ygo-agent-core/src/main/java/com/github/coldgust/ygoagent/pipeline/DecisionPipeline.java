package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelRecordFeed;
import com.github.coldgust.ygoagent.context.PhaseText;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.llm.service.JudgeAgent;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorKeeper;
import com.github.coldgust.ygoagent.pipeline.fallback.RuleBasedEngine;
import com.github.coldgust.ygoagent.pipeline.judge.LlmJudge;
import com.github.coldgust.ygoagent.pipeline.plan.PlacementResolver;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.pipeline.reflex.FallbackIntents;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicy;
import com.github.coldgust.ygoagent.planner.PlannerRuntime;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 三级流水（docs/planner-judge-memory-redesign.md §8.1）：
 * ⓪ 位置兑现 → ① 唯一合法 → ② 决策器判断 → ③ 兜底。
 * <p>本类只做编排：各级试答、失败原因（missReasons）收集、轨迹事件与回合记忆的收尾。
 * 各级实现：⓪①{@link PlacementResolver}/{@link ReflexPolicy}、②{@link LlmJudge}、
 * ③{@link RuleBasedEngine}（delegate）。
 * <p>fail-closed：任何一级失败静默落到下一级；任何异常落到 ③。
 * 硬边界：确定性部件只回答"能不能执行"，不回答"该怎么打"。
 */
public final class DecisionPipeline implements DecisionEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DecisionPipeline.class);

    private final ReflexPolicy reflex;
    private final PlannerRuntime planner;
    private final ContextPackBuilder packer;
    private final PipelineSettings settings;
    private final DecisionEngine delegate;
    private final DecisionTraceWriter traceWriter;
    private final VerboseLog trace;
    private final DuelChatMemory judgeMemory;
    private final DuelRecordFeed feed;
    private final TurnMemory memory = new TurnMemory();
    private final PlacementResolver placement;
    private final LlmJudge judge;

    /**
     * 可选依赖用 Builder 逐项声明；派生子件（PlacementResolver/LlmJudge）在构造器内组装——
     * 这里是它们的唯一生产性组装点。
     */
    public static Builder builder(ReflexPolicy reflex, PlannerRuntime planner,
                                  ContextPackBuilder packer, JudgeAgent judgeAgent,
                                  PipelineSettings settings, CardFacts facts) {
        return new Builder(reflex, planner, packer, judgeAgent, settings, facts);
    }

    /** 依赖收集器：必填六件套 + 决策器记忆件 + 可选兜底引擎/轨迹/日志（缺省走静默默认）。 */
    public static final class Builder {
        private final ReflexPolicy reflex;
        private final PlannerRuntime planner;
        private final ContextPackBuilder packer;
        private final JudgeAgent judgeAgent;
        private final PipelineSettings settings;
        private final CardFacts facts;
        private DecisionEngine delegate;
        private DecisionTraceWriter traceWriter;
        private VerboseLog trace;
        private DuelChatMemory judgeMemory;
        private DuelRecordFeed feed;

        private Builder(ReflexPolicy reflex, PlannerRuntime planner,
                        ContextPackBuilder packer, JudgeAgent judgeAgent,
                        PipelineSettings settings, CardFacts facts) {
            this.reflex = reflex;
            this.planner = planner;
            this.packer = packer;
            this.judgeAgent = judgeAgent;
            this.settings = settings;
            this.facts = facts;
        }

        /** 兜底决策引擎（③ 纯规则链；缺省 = 无兜底）。 */
        public Builder delegate(DecisionEngine value) {
            this.delegate = value;
            return this;
        }

        /** 决策轨迹落点（缺省 NOOP）。 */
        public Builder traceWriter(DecisionTraceWriter value) {
            this.traceWriter = value;
            return this;
        }

        /** 全链路日志（缺省全关）。 */
        public Builder trace(VerboseLog value) {
            this.trace = value;
            return this;
        }

        /** 决策器对话记忆（决策器记忆化必填；build() 缺件即抛）。 */
        public Builder judgeMemory(DuelChatMemory value) {
            this.judgeMemory = value;
            return this;
        }

        /** 决斗记录流写入方（决策器记忆化必填；build() 缺件即抛）。 */
        public Builder feed(DuelRecordFeed value) {
            this.feed = value;
            return this;
        }

        public DecisionPipeline build() {
            if (judgeMemory == null || feed == null) {
                throw new IllegalStateException(
                        "DecisionPipeline 缺 judgeMemory/feed（决策器记忆化的必填接线）");
            }
            return new DecisionPipeline(reflex, planner, packer, judgeAgent, settings, facts,
                    delegate, traceWriter, trace, judgeMemory, feed);
        }
    }

    private DecisionPipeline(ReflexPolicy reflex, PlannerRuntime planner,
                             ContextPackBuilder packer, JudgeAgent judgeAgent,
                             PipelineSettings settings, CardFacts facts,
                             DecisionEngine delegate, DecisionTraceWriter traceWriter,
                             VerboseLog trace, DuelChatMemory judgeMemory, DuelRecordFeed feed) {
        this.reflex = reflex;
        this.planner = planner;
        this.packer = packer;
        this.settings = settings;
        this.delegate = delegate;
        this.traceWriter = DecisionTraceWriter.noopOr(traceWriter);
        this.trace = VerboseLog.offOr(trace);
        this.judgeMemory = judgeMemory;
        this.feed = feed;
        this.placement = new PlacementResolver(memory);
        this.judge = new LlmJudge(judgeAgent, packer, settings, planner, memory,
                judgeMemory, feed, this.trace);
    }

    @Override
    public DuelDecision decide(DecisionPoint point, DecisionDeadline deadline) {
        long start = System.nanoTime();
        try {
            planner.observePoint(point);
        } catch (RuntimeException ignored) {
            // 规划器故障不影响决策
        }
        DuelDecision decision;
        String level;
        String levelLabel;
        List<String> missReasons = new ArrayList<>();
        try {
            decision = decideInner(point, deadline, missReasons);
            level = decision.pathKind().name();
            levelLabel = decision.pathKind().label();
        } catch (RuntimeException e) {
            log.warn("执行器异常，走 ③ 保底: {}", e.toString());
            decision = new DuelDecision(FallbackIntents.first(point), PathKind.FALLBACK, 0.0,
                    DecisionExplanation.of("执行器异常降级: " + e.getClass().getSimpleName()), Timing.ZERO);
            level = "EXCEPTION";
            levelLabel = "异常降级";
        }
        memory.rememberEffectScope(point, decision);
        long end = System.nanoTime();
        // 全链路日志：一行看清"这次是谁做的决定、花了多久、为什么没走上一级"
        trace.decision("seq=" + point.seq() + " T" + point.turn() + " " + point.kind().label()
                        + " → " + levelLabel + "（" + level + "）",
                "conf=" + String.format(Locale.ROOT, "%.2f", decision.confidence())
                        + " " + (end - start) / 1_000_000 + "ms"
                        + (missReasons.isEmpty() ? "" : " ｜ 未命中: " + String.join(" | ", missReasons))
                        + " ｜ 提交: " + trace.preview(decision.explanation().conclusion(), 80));
        // 前端要画"建议 → 决策"的因果与路径，把建议标识与解释一起发出去
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("levelLabel", levelLabel);
        payload.put("requestLabel", point.kind().label());
        // 前端描述与决策器对齐（2026-11-15 用户指令）：决策器看到的请求抬头（回合/谁的回合/阶段/
        // "此时可发动效果"等）、连锁逐环内容、阶段中文名一并发出——空链窗口不再显示成"连锁响应"，
        // 有连锁时前端能同步看到在响应什么。
        payload.put("phaseLabel", PhaseText.label(point.phase()));
        payload.put("requestHeadline", packer.requestHeadline(point));
        String chainLine = packer.compactChain(point);
        if (!chainLine.isEmpty()) {
            payload.put("chain", chainLine);
        }
        payload.put("intent", decision.intent().getClass().getSimpleName());
        payload.put("intentLabel", ChoiceIntent.label(decision.intent()));
        payload.put("confidence", decision.confidence());
        payload.put("missReasons", String.join(" | ", missReasons));
        String conclusion = decision.explanation().conclusion();
        payload.put("explanation", conclusion == null ? "" : conclusion);
        planner.currentAdvice().ifPresent(advice -> {
            payload.put("adviceId", advice.adviceId());
            payload.put("adviceTurn", advice.turn());
            payload.put("adviceStale", advice.turn() != point.turn());
            payload.put("adviceGoal", String.valueOf(advice.answer().goal()));
        });
        // ① 唯一合法照旧进轨迹（trace 文件与离线排查要一条不少）；前端不显示由 web 出口过滤，
        // 见 ygo-play-server 的 WebDecisionFeed。
        event(point, "EXEC_DECISION", payload);
        // 位置线索：本次选了会落场的动作 → 记住模型一并给出的格子；本次就是位置请求 → 用完清掉
        // （事件里给中文区域名，前端与日志都不需要再翻译代号）
        memory.rememberPlacement(point, decision).ifPresent(zone ->
                event(point, "POSITION_HINT", Map.of("zone", ZoneCodes.label(zone))));
        DuelDecision finalDecision = decision.withTiming(new Timing(0, end - start, end - start));
        try {
            traceWriter.append(point, finalDecision);
        } catch (RuntimeException ignored) {
        }
        return finalDecision;
    }

    private DuelDecision decideInner(DecisionPoint point, DecisionDeadline deadline,
                                     List<String> missReasons) {
        // ⓪ 位置兑现：决策器在选动作时已一并给出位置 → 直接用，不再额外问一次
        if (point.kind() == RequestKind.PLACE) {
            Optional<DuelDecision> placed = placement.resolve(point);
            if (placed.isPresent()) {
                trace.decision("⓪ 位置兑现", placed.get().explanation().conclusion());
                return placed.get();
            }
        }
        // ① 唯一合法（机械请求 + 已知表格策略）
        Optional<ChoiceIntent> quick = reflex.tryAnswer(point);
        if (quick.isPresent()) {
            trace.decision("① 唯一合法", quick.get().getClass().getSimpleName() + "（没有判断余地）");
            return new DuelDecision(quick.get(), PathKind.ONLY_CHOICE, 1.0,
                    DecisionExplanation.of("规则直觉命中"), Timing.ZERO);
        }
        if (!point.candidates().isEmpty()) {
            // 等建议：本回合首次决策器判断前，给幕僚的锚点一次机会（等待不计入决策预算）
            if (planner.currentAdvice().isEmpty()) {
                long waitMs = adviceWaitMs(memory.waitedForAdvice(point.turn()),
                        settings, planner.remainingTurnBudget());
                if (waitMs > 0 && planner.healthy()) {
                    memory.markWaitedForAdvice(point.turn());
                    long before = System.currentTimeMillis();
                    planner.waitForAdvice(waitMs);
                    long waited = System.currentTimeMillis() - before;
                    deadline = shiftedBy(deadline, waited); // 等待不计入决策预算
                    log.info("本回合首次决策器判断（{}）：等建议 {}ms，实际 {}ms，决策预算已顺延",
                            point.kind(), waitMs, waited);
                } else {
                    missReasons.add("本回合建议尚未就绪（锚点竞速/规划器降级）");
                }
            }
            // ② 决策器判断（带记忆 + 快照 + 规划器情报）
            long judgeStart = System.currentTimeMillis();
            Optional<DuelDecision> judged = judge.judge(point, deadline, missReasons);
            if (judged.isPresent()) {
                trace.step("② 决策器判断", System.currentTimeMillis() - judgeStart,
                        judged.get().pathKind().label() + " ｜ " + judged.get().explanation().conclusion());
                return judged.get();
            }
            missReasons.add("升级无有效结果（超时/校验失败）");
            log.debug("执行器降级到兜底: {}", missReasons);
        }
        // ③ 保底：原决策链路（Tactical/Arbiter/Fallback）
        DuelDecision fallback = delegate.decide(point, deadline);
        trace.decision("③ 兜底", fallback.pathKind().label() + " ｜ "
                + fallback.explanation().conclusion());
        return fallback;
    }

    /**
     * 本回合"等建议"的时长：整回合只有一次，时长统一，与请求类型/阶段无关。
     * <ul>
     *   <li>本回合已经等过 → 0；</li>
     *   <li>否则 → {@code firstAdviceWaitMs}（默认 7s），只受"本回合等待额度"约束；</li>
     *   <li>{@code waitPerDecisionMs=0} 表示显式关闭等待。</li>
     * </ul>
     * 等待不占用决策预算（调用方会把截止时间顺延）。
     */
    static long adviceWaitMs(boolean waitedThisTurn, PipelineSettings settings, long remainingTurnBudgetMs) {
        if (waitedThisTurn || settings.waitPerDecisionMs() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(settings.firstAdviceWaitMs(), remainingTurnBudgetMs));
    }

    /** 把截止时间整体后移（等待属于额外时间，不从决策预算里扣）。 */
    private static DecisionDeadline shiftedBy(DecisionDeadline deadline, long extraMillis) {
        return extraMillis <= 0 ? deadline
                : new DecisionDeadline(deadline.startNanos(),
                deadline.hardNanos() + extraMillis * 1_000_000L, deadline.safetyMillis());
    }

    private void event(DecisionPoint point, String kind, Map<String, Object> payload) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(payload);
            data.put("seq", point.seq());
            data.put("turn", point.turn());
            data.put("phase", String.valueOf(point.phase()));
            data.put("requestKind", point.kind().name());
            traceWriter.event(kind, data);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        judge.close();
        planner.close();
    }
}
