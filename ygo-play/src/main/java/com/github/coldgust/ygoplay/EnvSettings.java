package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.model.RequestKind;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI 环境配置（OS env + .env 合并源的类型化形态，键名只在 {@link #parse} 出现一次）。
 * 值不加工：absent = null（缺席语义由装配层决定）；present 但格式非法 → 抛异常点名键。
 *
 * <p>默认值分级：调优参数（等待预算/阈值/预算表/端口等机器无关项）由装配层与 core 配置对象给
 * 代码默认（{@code PipelineSettings.defaults}/{@code DuelTuning.defaults}/{@code RunnerConfig.defaults()}）；
 * 环境相关项（地址/路径/凭据）无代码默认，缺失由装配层抛异常点名。推荐默认值见 .env.example。
 */
public record EnvSettings(
        String ygoHost,
        Integer ygoPort,
        String ygoPlayerName,
        String ygoMove,
        String duelLogDir,
        String traceDir,
        String battleReport,
        String deckStrategyFile,
        String deckStrategy,
        String cardRagPath,
        String lightRagUrl,
        String lightRagApiKey,
        Long lightRagTimeoutMs,
        Boolean duelRawFrames,
        Long duelChoiceTimeoutMs,
        Long duelStartWaitMs,
        Integer comboContextLimit,
        Boolean planExecEnabled,
        String llmApiKey,
        String llmBaseUrl,
        String plannerModel,
        String llmModel,
        String reasoningPlanner,
        String reasoningDecision,
        Integer plannerWaitDecisionMs,
        Integer plannerFirstWaitMs,
        Integer plannerWaitTurnMs,
        Integer plannerMaxCheckpoints,
        Double plannerSimilarityThreshold,
        Integer judgeTimeoutMs,
        Integer plannerCallTimeoutMs,
        Integer plannerCooldownMs,
        Integer judgeMaxTokens,
        Boolean plannerPathPlanning,
        Boolean plannerRushAnswer,
        String verboseLog,
        String verboseMaxChars,
        Integer runnerPollMs,
        Integer runnerDefaultBudgetMs,
        Map<String, Long> runnerBudgets) {

    public static EnvSettings load(Path envFile) {
        return parse(DotEnv.load(envFile));
    }

    /** 测试注入口：键 = .env 键名的 map（未提供的键 = null）。 */
    public static EnvSettings of(Map<String, String> values) {
        return parse(Map.copyOf(values));
    }

    private static EnvSettings parse(Map<String, String> s) {
        return new EnvSettings(
                s.get("YGO_HOST"),
                intOf(s, "YGO_PORT"),
                s.get("YGO_PLAYER_NAME"),
                s.get("YGO_MOVE"),
                s.get("DUEL_LOG_DIR"),
                s.get("TRACE_DIR"),
                s.get("BATTLE_REPORT"),
                s.get("DECK_STRATEGY_FILE"),
                s.get("DECK_STRATEGY"),
                s.get("CARD_RAG_PATH"),
                s.get("LIGHTRAG_URL"),
                s.get("LIGHTRAG_API_KEY"),
                longOf(s, "LIGHTRAG_TIMEOUT_MS"),
                triBool(s, "DUEL_RAW_FRAMES"),
                longOf(s, "DUEL_CHOICE_TIMEOUT_MS"),
                longOf(s, "DUEL_START_WAIT_MS"),
                intOf(s, "COMBO_CONTEXT_LIMIT"),
                triBool(s, "PLAN_EXEC_ENABLED"),
                s.get("LLM_API_KEY"),
                s.get("LLM_BASE_URL"),
                s.get("PLANNER_MODEL"),
                s.get("LLM_MODEL"),
                s.get("LLM_REASONING_PLANNER"),
                s.get("LLM_REASONING_DECISION"),
                intOf(s, "PLANNER_WAIT_DECISION_MS"),
                intOf(s, "PLANNER_FIRST_WAIT_MS"),
                intOf(s, "PLANNER_WAIT_TURN_MS"),
                intOf(s, "PLANNER_MAX_CHECKPOINTS"),
                doubleOf(s, "PLANNER_SIMILARITY_THRESHOLD"),
                intOf(s, "JUDGE_TIMEOUT_MS"),
                intOf(s, "PLANNER_CALL_TIMEOUT_MS"),
                intOf(s, "PLANNER_COOLDOWN_MS"),
                intOf(s, "JUDGE_MAX_TOKENS"),
                boolOf(s, "PLANNER_PATH_PLANNING"),
                boolOf(s, "PLANNER_RUSH_ANSWER"),
                s.get("YGO_VERBOSE_LOG"),
                s.get("YGO_VERBOSE_MAX_CHARS"),
                intOf(s, "RUNNER_POLL_MS"),
                intOf(s, "RUNNER_DEFAULT_BUDGET_MS"),
                runnerBudgets(s));
    }

    /** 决策器逐类预算（键派生自 RequestKind 名，如 RUNNER_BUDGET_CHAIN_MS）。 */
    private static Map<String, Long> runnerBudgets(Map<String, String> s) {
        Map<String, Long> budgets = new HashMap<>();
        for (RequestKind kind : RequestKind.values()) {
            Long budget = longOf(s, "RUNNER_BUDGET_" + kind.name() + "_MS");
            if (budget != null) {
                budgets.put(kind.name(), budget);
            }
        }
        return Map.copyOf(budgets);
    }

    /** api key 不进日志（record 自动 toString 会原样打印敏感字段，这里遮蔽）；全量字段值经具名访问器获取。 */
    @Override
    public String toString() {
        return "EnvSettings[ygoHost=" + ygoHost + ", ygoPort=" + ygoPort + ", ygoPlayerName=" + ygoPlayerName
                + ", ygoMove=" + ygoMove + ", cardRagPath=" + cardRagPath
                + ", lightRagUrl=" + lightRagUrl + ", lightRagApiKey=***"
                + ", llmBaseUrl=" + llmBaseUrl + ", llmModel=" + (plannerModel != null ? plannerModel : llmModel)
                + ", llmApiKey=***, planExecEnabled=" + planExecEnabled
                + ", verboseLog=" + verboseLog + ", runnerBudgets=" + runnerBudgets + "]";
    }

    // ---- 解析辅助：present 但非法 → 抛点名；absent/空白 → null ----

    private static Integer intOf(Map<String, String> s, String key) {
        String raw = s.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 值非法: " + raw + "（应为整数）", e);
        }
    }

    private static Long longOf(Map<String, String> s, String key) {
        String raw = s.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 值非法: " + raw + "（应为整数）", e);
        }
    }

    private static Double doubleOf(Map<String, String> s, String key) {
        String raw = s.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 值非法: " + raw + "（应为小数）", e);
        }
    }

    /** 三态开关（PLAN_EXEC_ENABLED/DUEL_RAW_FRAMES）：true/1/on/yes → true，false/0/off/no → false，其余 → 未配置。 */
    private static Boolean triBool(Map<String, String> s, String key) {
        String raw = s.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        return v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("no")
                ? Boolean.FALSE
                : null;
    }

    /** 严格布尔（调优布尔旋钮）：absent/空白 → null；非 true/false 族 → 抛点名。 */
    private static Boolean boolOf(Map<String, String> s, String key) {
        String raw = s.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("no")) {
            return false;
        }
        throw new IllegalStateException("配置 " + key + " 值非法: " + raw + "（应为 true/false）");
    }
}
