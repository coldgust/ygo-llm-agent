package com.github.coldgust.ygoagent.decision.model;

import java.util.List;
import java.util.Map;

/** 一条候选行：动作序列 + 期望值 + 分项。 */
public record Line(
        String id,
        List<ChoiceIntent> steps,
        double expectedValue,
        Map<String, Double> terms,
        String evidence
) {

    public Line {
        steps = steps == null ? List.of() : List.copyOf(steps);
        terms = terms == null ? Map.of() : Map.copyOf(terms);
    }

    public ChoiceIntent firstStep() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("空行没有动作: " + id);
        }
        return steps.getFirst();
    }
}
