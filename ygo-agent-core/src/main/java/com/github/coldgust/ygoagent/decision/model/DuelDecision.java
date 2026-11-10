package com.github.coldgust.ygoagent.decision.model;

public record DuelDecision(
        ChoiceIntent intent,
        PathKind pathKind,
        double confidence,
        DecisionExplanation explanation,
        Timing timing
) {

    public DuelDecision {
        if (intent == null) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        explanation = explanation == null ? DecisionExplanation.of("") : explanation;
        timing = timing == null ? Timing.ZERO : timing;
    }

    public DuelDecision withTiming(Timing newTiming) {
        return new DuelDecision(intent, pathKind, confidence, explanation, newTiming);
    }
}
