package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.model.RequestKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 决策器预算表：轮询间隔、兜底预算与逐请求类型预算（毫秒）。
 * 调优参数——代码默认见 {@link #defaults()}（与 .env.example 的注释一致），宿主可经
 * {@code RUNNER_POLL_MS}/{@code RUNNER_DEFAULT_BUDGET_MS}/{@code RUNNER_BUDGET_<KIND>_MS} 覆盖；
 * 覆盖值 ≤0 → 构造期抛出。
 *
 * @param pollMillis          会话轮询间隔
 * @param defaultBudgetMillis 未单列预算的请求类型的兜底预算
 * @param budgets             逐请求类型预算（键 = RequestKind 名）
 */
public record RunnerConfig(Long pollMillis, Long defaultBudgetMillis, Map<RequestKind, Long> budgets) {

    public RunnerConfig {
        if (pollMillis == null || pollMillis <= 0) {
            throw new IllegalArgumentException("RunnerConfig pollMillis 必须为正数（RUNNER_POLL_MS）");
        }
        if (defaultBudgetMillis == null || defaultBudgetMillis <= 0) {
            throw new IllegalArgumentException("RunnerConfig defaultBudgetMillis 必须为正数（RUNNER_DEFAULT_BUDGET_MS）");
        }
        budgets = budgets == null ? Map.of() : Map.copyOf(budgets);
    }

    /** 调优默认（机器无关）。 */
    public static RunnerConfig defaults() {
        EnumMap<RequestKind, Long> budgets = new EnumMap<>(RequestKind.class);
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
        // 位置不为它单独发 LLM 请求：模型在计划步/直答 里一并给出 zone，
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
        return new RunnerConfig(25L, 200L, budgets);
    }

    /** CLI：环境覆盖叠加代码默认（未配置的键用 {@link #defaults()} 值）。 */
    public static RunnerConfig fromEnv(EnvSettings env) {
        RunnerConfig base = defaults();
        EnumMap<RequestKind, Long> budgets = new EnumMap<>(RequestKind.class);
        budgets.putAll(base.budgets());
        if (env.runnerBudgets() != null) {
            env.runnerBudgets().forEach((name, budget) -> budgets.put(RequestKind.valueOf(name), budget));
        }
        return new RunnerConfig(
                env.runnerPollMs() != null ? env.runnerPollMs() : base.pollMillis(),
                env.runnerDefaultBudgetMs() != null ? env.runnerDefaultBudgetMs() : base.defaultBudgetMillis(),
                budgets);
    }

    /** server：Spring 绑定的可选覆盖叠加代码默认（非法 RequestKind 名在启动期抛出）。 */
    public static RunnerConfig from(Integer pollMillis, Integer defaultBudgetMillis, Map<String, Long> budgetsByName) {
        RunnerConfig base = defaults();
        EnumMap<RequestKind, Long> budgets = new EnumMap<>(RequestKind.class);
        budgets.putAll(base.budgets());
        if (budgetsByName != null) {
            budgetsByName.forEach((name, budget) -> budgets.put(RequestKind.valueOf(name), budget));
        }
        return new RunnerConfig(
                pollMillis != null ? pollMillis : base.pollMillis(),
                defaultBudgetMillis != null ? defaultBudgetMillis : base.defaultBudgetMillis(),
                budgets);
    }

    public long budgetMillis(RequestKind kind) {
        return budgets.getOrDefault(kind, defaultBudgetMillis);
    }
}
