package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.pipeline.plan.PlanAssembler;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规划器计划状态的持有者（模块内部协作类型）。
 *
 * <p>拥有唯一的 {@code AtomicReference<TurnPlan>}：发布、keep 续期、作废骨架化、游标推进。
 * 无锁读语义不变（决策线程随时 {@link #current()}）；只在 planner 单线程（worker）上写入。
 */
final class PlanKeeper {

    private final AtomicReference<TurnPlan> currentPlan = new AtomicReference<>();

    Optional<TurnPlan> current() {
        return Optional.ofNullable(currentPlan.get());
    }

    TurnPlan currentOrNull() {
        return currentPlan.get();
    }

    boolean isEmpty() {
        return currentPlan.get() == null;
    }

    /** 发布新计划（段内新产出，cursor 从 0 起）。 */
    void publish(PlanAssembler.Parsed plan, String planId, int turnNumber) {
        currentPlan.set(new TurnPlan(planId, turnNumber, plan.thinking(), plan.goal(),
                plan.steps(), plan.branches(), 0, plan.confidence()));
    }

    /**
     * keep 续期：把上一回合的计划续到 newTurn——步骤与分支不变，cursor 归零，置信度打折。
     *
     * @return 续期结果（同回合 keep 或无计划 → empty，无需日志）
     */
    Optional<CarriedOver> carryOver(int newTurn) {
        TurnPlan previous = currentPlan.get();
        if (previous == null || previous.turnNumber() == newTurn) {
            return Optional.empty();
        }
        double confidence = Math.max(0.3, previous.confidence() * 0.7);
        currentPlan.set(new TurnPlan(previous.planId(), newTurn, previous.thinking(), previous.goal(),
                previous.steps(), previous.branches(), 0, confidence));
        return Optional.of(new CarriedOver(previous.planId(), previous.turnNumber(),
                previous.steps().size(), previous.branches().size(), confidence));
    }

    /** keep 续期的日志/事件载荷。 */
    record CarriedOver(String planId, int fromTurn, int steps, int branches, double confidence) {
    }

    /**
     * 作废处理（执行器发现计划接不上地时）：
     * <ul>
     *   <li>无计划 → empty（无事发生）；</li>
     *   <li>已是骨架（只有战术思想）→ 原样保留并返回 empty（不重复请求重规划）；</li>
     *   <li>有路径 → 保留 thinking/goal 作骨架（steps 清空、置信度减半），返回原计划——
     *       决策器没有主线时会打出与整体思路背道而驰的操作，骨架继续喂给 直答。</li>
     * </ul>
     */
    Optional<TurnPlan> abandonToSkeleton() {
        TurnPlan stale = currentPlan.getAndSet(null);
        if (stale == null) {
            return Optional.empty();
        }
        if (stale.steps().isEmpty()) {
            currentPlan.set(stale);
            return Optional.empty();
        }
        currentPlan.set(new TurnPlan(stale.planId(), stale.turnNumber(), stale.thinking(), stale.goal(),
                List.of(), List.of(), 0, Math.max(0.2, stale.confidence() * 0.5)));
        return Optional.of(stale);
    }

    void advanceCursorTo(int nextCursor) {
        TurnPlan plan = currentPlan.get();
        if (plan != null && nextCursor > plan.cursor()) {
            currentPlan.set(new TurnPlan(plan.planId(), plan.turnNumber(), plan.thinking(), plan.goal(),
                    plan.steps(), plan.branches(), Math.min(nextCursor, plan.steps().size()),
                    plan.confidence()));
        }
    }

    /** 发布事件的载荷：前端要画"计划 → 决策"的因果，步骤/分支明细在这里展开。 */
    Map<String, Object> publishedPayload(String planId, PlanAssembler.Parsed plan,
                                         String checkpoint, long elapsedMs) {
        List<Map<String, Object>> stepDetails = plan.steps().stream()
                .map(st -> Map.<String, Object>of(
                        "index", st.index(),
                        "when", st.expectedRequest().name(),
                        "verb", st.alternatives().isEmpty() ? ""
                                : st.alternatives().getFirst().verb(),
                        "passcode", st.alternatives().isEmpty() ? 0L
                                : st.alternatives().getFirst().passcode(),
                        // 位置也是计划的一部分：前端要能看出"召唤到哪一格"
                        "zone", st.alternatives().isEmpty()
                                || st.alternatives().getFirst().zone() == null ? ""
                                : ZoneCodes.code(st.alternatives().getFirst().zone()),
                        "why", String.valueOf(st.expectedEffects().getOrDefault("why", ""))))
                .toList();
        List<Map<String, Object>> branchDetails = plan.branches().stream()
                .map(br -> Map.<String, Object>of(
                        "name", br.name(),
                        "assumes", br.assumes().toString(),
                        "steps", br.steps().stream()
                                .map(st -> (st.alternatives().isEmpty() ? "?"
                                        : st.alternatives().getFirst().verb())
                                        + (st.alternatives().isEmpty()
                                        || st.alternatives().getFirst().passcode() <= 0 ? ""
                                        : " " + st.alternatives().getFirst().passcode()))
                                .toList()))
                .toList();
        return Map.of(
                "planId", planId, "steps", plan.steps().size(),
                "branches", plan.branches().size(), "confidence", plan.confidence(),
                "thinking", plan.thinking(), "goal", plan.goal(),
                "checkpoint", checkpoint, "elapsedMs", elapsedMs,
                "stepDetails", stepDetails, "branchDetails", branchDetails);
    }
}
