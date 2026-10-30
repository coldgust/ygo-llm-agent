package com.github.coldgust.ygoagent.decision.engine;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionExplanation;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.EvidencePack;
import com.github.coldgust.ygoagent.decision.model.PathKind;
import com.github.coldgust.ygoagent.decision.model.RankedLines;
import com.github.coldgust.ygoagent.decision.model.Timing;
import com.github.coldgust.ygoagent.decision.planexec.PlanExecutorDecisionPort;
import com.github.coldgust.ygoagent.decision.port.DecisionArbiter;
import com.github.coldgust.ygoagent.decision.port.DecisionPort;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.port.ReflexPolicy;
import com.github.coldgust.ygoagent.decision.port.TacticalPlanner;
import com.github.coldgust.ygoagent.decision.reflex.FallbackIntents;

/**
 * 保底决策链路（v6 执行器 ⑤）：Reflex → Tactical → Arbiter，异常降级到保底意图。
 * <p>v6 中它作为 {@link PlanExecutorDecisionPort}
 * 的最后一道兜底；单独使用时即 v2 的完整快路径。
 */
public final class DefaultDecisionPort implements DecisionPort {

    private final ReflexPolicy reflex;
    private final TacticalPlanner tactical;
    private final DecisionArbiter arbiter;
    private final DecisionTraceWriter traceWriter;

    public DefaultDecisionPort(ReflexPolicy reflex, TacticalPlanner tactical,
                               DecisionArbiter arbiter, DecisionTraceWriter traceWriter) {
        this.reflex = reflex;
        this.tactical = tactical;
        this.arbiter = arbiter;
        this.traceWriter = traceWriter == null ? DecisionTraceWriter.NOOP : traceWriter;
    }

    @Override
    public DuelDecision decide(DecisionPoint point, DecisionDeadline deadline) {
        long start = System.nanoTime();
        DuelDecision decision;
        try {
            var quick = reflex.tryAnswer(point);
            if (quick.isPresent()) {
                decision = new DuelDecision(quick.get(), PathKind.ONLY_CHOICE, 1.0,
                        DecisionExplanation.of("规则命中：" + quick.get().getClass().getSimpleName()),
                        Timing.ZERO);
            } else {
                RankedLines ranked = tactical.plan(point, EvidencePack.empty(), deadline);
                decision = arbiter.choose(point, ranked);
            }
        } catch (RuntimeException e) {
            decision = new DuelDecision(FallbackIntents.first(point), PathKind.FALLBACK, 0.0,
                    DecisionExplanation.of("决策异常降级：" + e.getClass().getSimpleName() + ": " + e.getMessage()),
                    Timing.ZERO);
        }
        long end = System.nanoTime();
        DuelDecision finalDecision = decision.withTiming(new Timing(0, end - start, end - start));
        try {
            traceWriter.append(point, finalDecision);
        } catch (RuntimeException ignored) {
            // 轨迹写入不得影响决策
        }
        return finalDecision;
    }
}
