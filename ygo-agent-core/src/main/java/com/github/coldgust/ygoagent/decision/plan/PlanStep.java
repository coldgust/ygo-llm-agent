package com.github.coldgust.ygoagent.decision.plan;

import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.List;
import java.util.Map;

/**
 * 计划中的一步（v6 执行器 ②③ 的接地单元；多步分支在 planexec.TurnBranch）。
 *
 * @param index           步骤下标
 * @param expectedRequest 该步应在哪个决策点执行
 * @param alternatives    可接受的语义动作（任一命中即接地）
 * @param preconditions   前置条件（语义键值，供校验扩展）
 * @param expectedEffects 预期状态变化（含 "why"，供解释与 trace）
 * @param confidence      置信度
 */
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
