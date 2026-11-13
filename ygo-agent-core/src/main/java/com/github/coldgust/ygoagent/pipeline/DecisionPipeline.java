package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.IntentSummary;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.ComboTool;
import com.github.coldgust.ygoagent.llm.service.AgentChatServices;
import com.github.coldgust.ygoagent.llm.DecisionTools;
import com.github.coldgust.ygoagent.llm.RulebookTool;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.fallback.RuleBasedEngine;
import com.github.coldgust.ygoagent.pipeline.judge.LlmJudge;
import com.github.coldgust.ygoagent.pipeline.plan.PlanFollower;
import com.github.coldgust.ygoagent.pipeline.plan.PlanHit;
import com.github.coldgust.ygoagent.pipeline.plan.PlanStep;
import com.github.coldgust.ygoagent.pipeline.plan.PlacementResolver;
import com.github.coldgust.ygoagent.pipeline.plan.SimilarityMatcher;
import com.github.coldgust.ygoagent.pipeline.plan.StepGrounder;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.pipeline.reflex.FallbackIntents;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicy;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 五级流水主干（§4.2）：⓪ 位置兑现 → ① 唯一合法 → ② 照计划 → ③ 走预案 → ④ 决策器判断 → ⑤ 兜底。
 * <p>本类只做编排：各级试答、失败原因（missReasons）收集、轨迹事件与回合记忆的收尾。
 * 各级实现：<①{@link ReflexPolicy}、⓪②③{@link PlacementResolver}/{@link PlanFollower}、
 * ④{@link LlmJudge}、⑤{@link RuleBasedEngine}（delegate）。
 * <p>fail-closed：任何一级失败静默落到下一级；任何异常落到 ⑤。
 * 硬边界（§2.2.10）：确定性部件只回答"计划能否兑现"，不回答"该怎么打"。
 */
public final class DecisionPipeline implements DecisionEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DecisionPipeline.class);

    private final ReflexPolicy reflex;
    private final PlannerSession planner;
    private final PipelineSettings settings;
    private final CardFacts facts;
    private final DecisionEngine delegate;
    private final DecisionTraceWriter traceWriter;
    private final VerboseLog trace;
    private final IntentSummary intentSummary;
    private final TurnMemory memory = new TurnMemory();
    private final PlacementResolver placement;
    private final PlanFollower follower;
    private final LlmJudge judge;

    public DecisionPipeline(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, AgentChatServices chat,
                                    PipelineSettings settings, CardFacts facts,
                                    DecisionEngine delegate, DecisionTraceWriter traceWriter,
                                    VerboseLog trace) {
        this(reflex, planner, packer, chat, settings, facts, delegate, traceWriter,
                DecisionTools.NONE, trace);
    }

    public DecisionPipeline(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, AgentChatServices chat,
                                    PipelineSettings settings, CardFacts facts,
                                    DecisionEngine delegate, DecisionTraceWriter traceWriter,
                                    DecisionTools tools, VerboseLog trace) {
        this.reflex = reflex;
        this.planner = planner;
        this.settings = settings;
        this.facts = facts;
        this.delegate = delegate;
        this.traceWriter = traceWriter == null ? DecisionTraceWriter.NOOP : traceWriter;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
        this.intentSummary = new IntentSummary(facts);
        this.placement = new PlacementResolver(planner, memory);
        this.follower = new PlanFollower(planner, new StepGrounder(), new SimilarityMatcher(),
                settings, memory, this.trace);
        this.judge = new LlmJudge(chat, packer, tools, settings, planner, memory, this.trace);
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
        Integer planStep = null;
        String level;
        String levelLabel;
        List<String> missReasons = new ArrayList<>();
        try {
            Outcome outcome = decideInner(point, deadline, missReasons);
            decision = outcome.decision();
            planStep = outcome.planStep();
            level = decision.pathKind().name();
            levelLabel = decision.pathKind().label();
        } catch (RuntimeException e) {
            log.warn("执行器异常，走 ⑤ 保底: {}", e.toString());
            decision = new DuelDecision(FallbackIntents.first(point), PathKind.FALLBACK, 0.0,
                    DecisionExplanation.of("执行器异常降级: " + e.getClass().getSimpleName()), Timing.ZERO);
            level = "EXCEPTION";
            levelLabel = "异常降级";
        }
        memory.rememberEffectScope(point, decision);
        recordTurnAction(point, decision);
        try {
            planner.observeAnswered(point, decision.intent());
            if (point.kind() == RequestKind.MAIN_PHASE || point.kind() == RequestKind.BATTLE_PHASE) {
                planner.flushEvents();
            }
        } catch (RuntimeException ignored) {
        }
        long end = System.nanoTime();
        // 全链路日志：一行看清"这次是谁做的决定、花了多久、为什么没走上一级"
        trace.decision("seq=" + point.seq() + " T" + point.turn() + " " + point.kind().label()
                        + " → " + levelLabel + "（" + level + "）",
                "conf=" + String.format(Locale.ROOT, "%.2f", decision.confidence())
                        + " " + (end - start) / 1_000_000 + "ms"
                        + (missReasons.isEmpty() ? "" : " ｜ 未命中: " + String.join(" | ", missReasons))
                        + " ｜ 提交: " + trace.preview(decision.explanation().conclusion(), 80));
        // 前端要画"计划 → 决策"的因果与路径，所以把计划进度、命中步、解释一起发出去
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("levelLabel", levelLabel);
        payload.put("requestLabel", point.kind().label());
        payload.put("intent", decision.intent().getClass().getSimpleName());
        payload.put("intentLabel", ChoiceIntent.label(decision.intent()));
        payload.put("confidence", decision.confidence());
        payload.put("missReasons", String.join(" | ", missReasons));
        String conclusion = decision.explanation().conclusion();
        payload.put("explanation", conclusion == null ? "" : conclusion);
        TurnPlan plan = planner.currentPlan().orElse(null);
        if (plan != null) {
            payload.put("planId", plan.planId());
            payload.put("planCursor", plan.cursor());
            payload.put("planSteps", plan.steps().size());
            payload.put("planGoal", plan.goal());
        }
        if (planStep != null) {
            payload.put("planStep", planStep);
        }
        event(point, "EXEC_DECISION", payload);
        // 位置线索：本次选了会落场的动作 → 记住模型一并给出的格子；本次就是位置请求 → 用完清掉
        memory.rememberPlacement(point, decision).ifPresent(zone ->
                event(point, "POSITION_HINT", Map.of("zone", ZoneCodes.code(zone))));
        DuelDecision finalDecision = decision.withTiming(new Timing(0, end - start, end - start));
        try {
            traceWriter.append(point, finalDecision);
        } catch (RuntimeException ignored) {
        }
        return finalDecision;
    }

    /**
     * 记一笔"我刚做过什么、刚才是怎么想的"（供 ④ 的单轮调用补上下文）。
     */
    private void recordTurnAction(DecisionPoint point, DuelDecision decision) {
        memory.recordTurnAction(point.turn(),
                trace.preview(decision.explanation() == null
                        ? "" : decision.explanation().conclusion(), 80),
                intentSummary.of(decision.intent(), point));
    }

    /** 一次编排的结果：决策本体 + 结构化的命中步号（无计划命中为 null）。 */
    private record Outcome(DuelDecision decision, Integer planStep) {
    }

    private Outcome decideInner(DecisionPoint point, DecisionDeadline deadline, List<String> missReasons) {
        // ⓪ 位置兑现：模型在选动作时已一并给出位置 → 直接用，不再额外问一次
        if (point.kind() == RequestKind.PLACE) {
            Optional<DuelDecision> placed = placement.resolve(point);
            if (placed.isPresent()) {
                trace.decision("⓪ 位置兑现", placed.get().explanation().conclusion());
                return new Outcome(placed.get(), null);
            }
        }
        // ① Reflex（机械请求 + 已知表格策略）
        Optional<ChoiceIntent> quick = reflex.tryAnswer(point);
        if (quick.isPresent()) {
            trace.decision("① 唯一合法", quick.get().getClass().getSimpleName() + "（没有判断余地）");
            return new Outcome(new DuelDecision(quick.get(), PathKind.ONLY_CHOICE, 1.0,
                    DecisionExplanation.of("规则直觉命中"), Timing.ZERO), null);
        }
        if (!point.candidates().isEmpty()) {
            Optional<TurnPlan> planOpt = planner.currentPlan()
                    .filter(p -> p.turnNumber() == point.turn());
            if (planOpt.isEmpty()) {
                long waitMs = planWaitMs(memory.waitedForPlan(point.turn()),
                        settings, planner.remainingTurnBudget());
                if (waitMs > 0 && planner.healthy()) {
                    memory.markWaitedForPlan(point.turn());
                    long before = System.currentTimeMillis();
                    planOpt = planner.waitForPlan(point.turn(), waitMs);
                    long waited = System.currentTimeMillis() - before;
                    deadline = shiftedBy(deadline, waited); // 等待不计入决策预算
                    log.info("本回合首次决策器判断（{}）：等计划 {}ms，实际 {}ms，决策预算已顺延",
                            point.kind(), waitMs, waited);
                }
            }
            TurnPlan plan = planOpt.orElse(null);
            if (plan != null) {
                // ② 计划精确匹配
                Optional<PlanHit> exact = follower.tryExact(point, plan);
                if (exact.isPresent()) {
                    return new Outcome(exact.get().decision(), exact.get().stepIndex());
                }
                if (plan.steps().isEmpty()) {
                    // 骨架计划：不接地、不重复作废，直接进 ④，
                    // 但计划上下文仍会把它带给 直答，避免决策与战术思想背道而驰。
                    missReasons.add(settings.pathPlanning()
                            ? "计划路径已作废，仅保留战术思想（改决策器判断）"
                            : "顾问模式：无固定路径，按战术建议决策器判断");
                } else if (!plan.active()) {
                    missReasons.add("计划已走完（cursor=" + plan.cursor() + "/"
                            + plan.steps().size() + "）");
                } else if (subChoice(point.kind())) {
                    missReasons.add("本决策点是附属选择（" + point.kind().label()
                            + "），计划主线仍然有效，按决策器判断");
                } else {
                    missReasons.add("计划步未接地（cursor=" + plan.cursor() + "/"
                            + plan.steps().size() + "）");
                    // ③ 相似匹配（stakes 高时跳过，§2.2.4）：
                    // 分支是"另一条计划"，主线接不上时它仍可能成立，所以先给分支一次机会。
                    if (!highStakes(point)) {
                        Optional<PlanHit> similar = follower.trySimilar(point, plan);
                        if (similar.isPresent()) {
                            return new Outcome(similar.get().decision(), similar.get().stepIndex());
                        }
                        missReasons.add("无分支相似命中（阈值 " + settings.similarityThreshold() + "）");
                    } else {
                        missReasons.add("相似匹配跳过（高 stakes）");
                    }
                    // ②③ 全落空 → 这份计划的前提已经不成立：整份作废，退回决策器判断。
                    abandonUngroundedPlan(plan, point, missReasons);
                    plan = null;
                }
            } else {
                missReasons.add("本回合计划尚未就绪（锚点竞速/规划器降级）");
            }


            // ④ 投机双路：不依赖计划存在（直答 是独立路径，实机 r2 的教训）
            long judgeStart = System.currentTimeMillis();
            Optional<DuelDecision> judged = judge.judge(point, deadline, missReasons);
            if (judged.isPresent()) {
                trace.step("④ 决策器判断", System.currentTimeMillis() - judgeStart,
                        judged.get().pathKind().label() + " ｜ " + judged.get().explanation().conclusion());
                return new Outcome(judged.get(), null);
            }
            missReasons.add("升级无有效结果（超时/校验失败）");
            log.debug("执行器降级到决策器判断/兜底: {}", missReasons);
        }
        // ⑤ 保底：原决策链路（Tactical/Arbiter/Fallback）
        DuelDecision fallback = delegate.decide(point, deadline);
        trace.decision("⑤ 兜底", fallback.pathKind().label() + " ｜ "
                + fallback.explanation().conclusion());
        return new Outcome(fallback, null);
    }

    /**
     * 本回合"等计划"的时长：整回合只有一次，时长统一，与请求类型/阶段无关。
     * <ul>
     *   <li>本回合已经等过 → 0；</li>
     *   <li>否则 → {@code firstJudgeWaitMs}（默认 7s），只受"本回合等待额度"约束；</li>
     *   <li>{@code waitPerDecisionMs=0} 表示显式关闭等待。</li>
     * </ul>
     * 等待不占用决策预算（调用方会把截止时间顺延），所以这里不需要为 直答 预留时间。
     */
    static long planWaitMs(boolean waitedThisTurn, PipelineSettings settings, long remainingTurnBudgetMs) {
        if (waitedThisTurn || settings.waitPerDecisionMs() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(settings.firstJudgeWaitMs(), remainingTurnBudgetMs));
    }

    /** 把截止时间整体后移（等待属于额外时间，不从决策预算里扣）。 */
    private static DecisionDeadline shiftedBy(DecisionDeadline deadline, long extraMillis) {
        return extraMillis <= 0 ? deadline
                : new DecisionDeadline(deadline.startNanos(),
                deadline.hardNanos() + extraMillis * 1_000_000L, deadline.safetyMillis());
    }

    /**
     * 计划接地失败 → 整份作废 + 请求重新规划。
     */
    private void abandonUngroundedPlan(TurnPlan plan, DecisionPoint point, List<String> missReasons) {
        int cursor = plan.cursor();
        PlanStep stuck = cursor < plan.steps().size() ? plan.steps().get(cursor) : null;
        String desc = stuck == null || stuck.alternatives().isEmpty() ? "?"
                : stuck.alternatives().getFirst().verb() + " "
                  + stuck.alternatives().getFirst().passcode();
        missReasons.add("计划作废：第 " + (cursor + 1) + " 步 [" + desc + "] 接不上地（"
                + cursor + "/" + plan.steps().size() + "），改决策器判断");
        event(point, "PLAN_ABANDONED", Map.of("planId", plan.planId(), "cursor", cursor,
                "steps", plan.steps().size(), "action", desc));
        log.info("计划接地失败（{}，第 {} 步 [{}]）→ 作废并请求重新规划", plan.planId(), cursor + 1, desc);
        try {
            planner.abandonAndReplan("第 " + (cursor + 1) + " 步 [" + desc + "] 在当前局面接不上地");
        } catch (RuntimeException ignored) {
            // 规划器故障不影响本决策：下面直接走 ④ 决策器判断
        }
    }

    /**
     * 计划主线之外的"派生请求"：位置、表示形式、选卡/祭品/排序/宣言，
     * 以及连锁与"是否发动"——这两类在计划里只是<b>预测</b>（"对手检索就丢灰"），
     * 预测没命中不等于计划破产。
     *
     * <p>它们接不上地时<b>只降级到决策器判断</b>，不作废、不重规划。
     * 只有主线动作（主阶段/战阶那几步）接不上地，才说明计划前提真的崩了。
     */
    private static boolean subChoice(RequestKind kind) {
        return switch (kind) {
            case PLACE, DISFIELD, POSITION, CARD_SELECT, TRIBUTE_SELECT, SUM, UNSELECT, SORT,
                 OPTION, DECLARE_RACE, DECLARE_ATTRIBUTE, DECLARE_CARD, DECLARE_NUMBER,
                 YES_NO, COUNTER, CHAIN, EFFECT_YES_NO -> true;
            default -> false;
        };
    }

    /** 高 stakes 判定里的 LP 危急线（确定性近似的阈值，非规则事实）。 */
    private static final int LOW_LP_THRESHOLD = 3000;

    /** 高 stakes 判定（slice 1 的确定性近似：对方已知攻击总和可斩 或 我方 LP 危急）。 */
    private boolean highStakes(DecisionPoint point) {
        var state = point.state();
        int oppAttack = state.monsters(PlayerSide.OPPONENT).stream()
                .filter(c -> c.position() != null && c.position().isAttack())
                .mapToInt(c -> facts.byPasscode(c.passcode()).map(CardFact::atk).orElse(0))
                .sum();
        return (oppAttack >= state.self().lifePoints() && oppAttack > 0)
                || state.self().lifePoints() <= LOW_LP_THRESHOLD;
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
