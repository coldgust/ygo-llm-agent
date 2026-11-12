package com.github.coldgust.ygoagent.model;

import java.util.List;
import java.util.Map;

public record DecisionExplanation(
        String conclusion,
        List<String> evidence,
        List<String> alternatives,
        Map<String, Double> utilityTerms
) {

    public DecisionExplanation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        utilityTerms = utilityTerms == null ? Map.of() : Map.copyOf(utilityTerms);
    }

    public static DecisionExplanation of(String conclusion) {
        return new DecisionExplanation(conclusion, List.of(), List.of(), Map.of());
    }
}
