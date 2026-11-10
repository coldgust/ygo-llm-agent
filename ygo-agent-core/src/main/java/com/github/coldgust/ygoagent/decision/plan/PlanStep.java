package com.github.coldgust.ygoagent.decision.plan;

import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.List;
import java.util.Map;

public record PlanStep(
        int index,
        RequestKind expectedRequest,
        List<ActionSpec> alternatives,
        Map<String, Object> preconditions,
        Map<String, Object> expectedEffects,
        double confidence) {

    public PlanStep {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        preconditions = preconditions == null ? Map.of() : Map.copyOf(preconditions);
        expectedEffects = expectedEffects == null ? Map.of() : Map.copyOf(expectedEffects);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
