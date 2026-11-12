package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.EvidencePack;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RankedLines;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.DecisionPipeline;
import com.github.coldgust.ygoagent.pipeline.fallback.DecisionArbiter;
import com.github.coldgust.ygoagent.pipeline.DecisionEngine;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicy;
import com.github.coldgust.ygoagent.pipeline.fallback.TacticalPlanner;
import com.github.coldgust.ygoagent.pipeline.reflex.FallbackIntents;

public final class RuleBasedEngine implements DecisionEngine {

    private final ReflexPolicy reflex;
    private final TacticalPlanner tactical;
    private final DecisionArbiter arbiter;
    private final DecisionTraceWriter traceWriter;

    public RuleBasedEngine(ReflexPolicy reflex, TacticalPlanner tactical,
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
