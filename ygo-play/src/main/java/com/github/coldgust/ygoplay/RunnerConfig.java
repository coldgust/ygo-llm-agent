package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * 决策预算表（数值仅用于 deadline 传播；v6 复杂决策等待见 PlanExecSettings）。
 */
public record RunnerConfig(long pollMillis, long defaultBudgetMillis, Map<RequestKind, Long> budgets) {

    public RunnerConfig {
        budgets = budgets == null ? Map.of() : Map.copyOf(budgets);
    }

    public static RunnerConfig defaults() {
        EnumMap<RequestKind, Long> budgets = new EnumMap<>(RequestKind.class);
        // REFLEX 现在只答"零策略"请求；凡是让行的请求（连锁/是否发动/选卡/表示形式…）都要真算一遍，
        // 预算必须够一次 LLM 往返。实测 deepseek-flash 单次决策 ≈2.4s（小 prompt），
        // 带完整局面的 decisionPack 通常 3–6s，所以这里给到 3–5s，主阶段更高。
        budgets.put(RequestKind.CHAIN, 6_000L);
        budgets.put(RequestKind.EFFECT_YES_NO, 4_000L);
        budgets.put(RequestKind.POSITION, 3_000L);
        budgets.put(RequestKind.CARD_SELECT, 6_000L);
        budgets.put(RequestKind.TRIBUTE_SELECT, 6_000L);
        budgets.put(RequestKind.SUM, 6_000L);
        budgets.put(RequestKind.UNSELECT, 5_000L);
        budgets.put(RequestKind.SORT, 5_000L);
        // 结构类请求仍由 REFLEX 直接答，毫秒级足够
        budgets.put(RequestKind.YES_NO, 100L);
        // 位置不为它单独发 LLM 请求：模型在计划步/one-shot 里一并给出 zone，
        // 兑现不了就直接取第一个可放置区域（毫秒级），所以这里保持很小的预算。
        budgets.put(RequestKind.PLACE, 100L);
        budgets.put(RequestKind.DISFIELD, 100L);
        budgets.put(RequestKind.COUNTER, 150L);
        budgets.put(RequestKind.OPTION, 100L);
        budgets.put(RequestKind.DECLARE_RACE, 100L);
        budgets.put(RequestKind.DECLARE_ATTRIBUTE, 100L);
        budgets.put(RequestKind.DECLARE_CARD, 100L);
        budgets.put(RequestKind.DECLARE_NUMBER, 100L);
        // v6：慢决策允许等规划器/LLM（速度可牺牲）
        budgets.put(RequestKind.MAIN_PHASE, 12_000L);
        budgets.put(RequestKind.BATTLE_PHASE, 10_000L);
        return new RunnerConfig(25, 200, budgets);
    }

    public long budgetMillis(RequestKind kind) {
        return budgets.getOrDefault(kind, defaultBudgetMillis);
    }

    public RunnerConfig withBudget(RequestKind kind, long millis) {
        EnumMap<RequestKind, Long> copy = new EnumMap<>(budgets);
        copy.put(kind, Math.max(100, millis));
        return new RunnerConfig(pollMillis, defaultBudgetMillis, copy);
    }
}
