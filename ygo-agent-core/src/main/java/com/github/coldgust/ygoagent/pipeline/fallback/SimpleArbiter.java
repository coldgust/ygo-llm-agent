package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.Line;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RankedLines;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.fallback.DecisionArbiter;
import com.github.coldgust.ygoagent.pipeline.reflex.FallbackIntents;

import java.util.ArrayList;
import java.util.List;

public final class SimpleArbiter implements DecisionArbiter {

    @Override
    public DuelDecision choose(DecisionPoint point, RankedLines ranked) {
        Line best = ranked.best();
        if (best == null) {
            return new DuelDecision(
                    FallbackIntents.first(point),
                    PathKind.FALLBACK,
                    0.0,
                    DecisionExplanation.of("无候选行，使用保底动作"),
                    Timing.ZERO);
        }
        List<String> alternatives = new ArrayList<>();
        ranked.lines().stream().skip(1).limit(3).forEach(line ->
                alternatives.add(line.id() + " value=" + line.expectedValue()));
        DecisionExplanation explanation = new DecisionExplanation(
                "选择 " + best.id() + "（" + best.evidence() + "）",
                List.of(best.evidence()),
                alternatives,
                best.terms());
        PathKind kind = best.id().startsWith("COMBO:") ? PathKind.COMBO : PathKind.TACTICAL;
        return new DuelDecision(best.firstStep(), kind, ranked.confidence(),
                explanation, Timing.ZERO);
    }
}
