package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.plan.ActionSpec;
import com.github.coldgust.ygoagent.pipeline.plan.PlanStep;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 回合计划（规划器产出，v6 §2.1）：主计划 + 分支（assumes 封闭词表）。
 * <p>不可变：推进 cursor / 更新计划都产生新实例（执行器无锁读）。
 * 复用既有 {@link PlanStep} / {@link ActionSpec}（动作词表与接地语义共用）。
 */
public record TurnPlan(
        String planId,
        int turnNumber,
        String thinking,
        String goal,
        List<PlanStep> steps,
        List<TurnBranch> branches,
        int cursor,
        double confidence) {

    public TurnPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        branches = branches == null ? List.of() : List.copyOf(branches);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public boolean active() {
        return cursor < steps.size();
    }

    public TurnPlan advanced() {
        return new TurnPlan(planId, turnNumber, thinking, goal, steps, branches,
                Math.min(cursor + 1, steps.size()), confidence);
    }

    /** 分支：assumes 为封闭词表特征（phase/opp_monsters/opp_backrow/hand_bucket/lp_bucket/key_cards）。 */
    public record TurnBranch(String name, Map<String, Object> assumes, List<PlanStep> steps) {
        public TurnBranch {
            assumes = assumes == null ? Map.of() : Map.copyOf(assumes);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public static RequestKind requestKindOf(String when) {
        return switch (when == null ? "" : when.trim().toUpperCase(Locale.ROOT)) {
            case "MAIN1", "MAIN", "MAIN2", "EP", "END_PHASE" -> RequestKind.MAIN_PHASE;
            case "BATTLE", "BP" -> RequestKind.BATTLE_PHASE;
            case "CHAIN" -> RequestKind.CHAIN;
            case "PLACE", "SELECT_PLACE" -> RequestKind.PLACE;
            case "CARD_SELECT" -> RequestKind.CARD_SELECT;
            default -> RequestKind.MAIN_PHASE;
        };
    }
}
