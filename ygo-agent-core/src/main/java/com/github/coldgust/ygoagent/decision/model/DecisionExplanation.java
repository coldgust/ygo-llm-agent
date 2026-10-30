package com.github.coldgust.ygoagent.decision.model;

import java.util.List;
import java.util.Map;

/**
 * 结构化解释：结论 + 证据 + 否决原因 + 分项。
 */
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
