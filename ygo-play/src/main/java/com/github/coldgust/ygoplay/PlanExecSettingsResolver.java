package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.planexec.PlannerSettings;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTuning;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class PlanExecSettingsResolver {

    public static Optional<PlannerSettings> resolve(Map<String, String> env) {
        // 思考强度走 LlmTuning 的进程级单例：.env 的值只有经过这里（真实环境变量优先）才生效
        LlmTuning.init(env);
        boolean explicit = truthy(env.get("PLAN_EXEC_ENABLED"));
        boolean envKeyPresent = env.get("LLM_API_KEY") != null && !env.get("LLM_API_KEY").isBlank();
        boolean enabled = explicit || (envKeyPresent && !falsy(env.get("PLAN_EXEC_ENABLED")));
        if (!enabled) {
            return Optional.empty();
        }
        String apiKey = env.get("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String baseUrl = firstNonBlank(env.get("LLM_BASE_URL"), "https://api.deepseek.com");
        String model = firstNonBlank(env.get("PLANNER_MODEL"), env.get("LLM_MODEL"), "deepseek-chat");
        String cardRag = firstNonBlank(env.get("CARD_RAG_PATH"), "data/card_rag/cards.jsonl");
        PlannerSettings defaults = PlannerSettings.defaults(Path.of(cardRag), baseUrl, apiKey, model);
        return Optional.of(new PlannerSettings(
                true,
                defaults.baseUrl(),
                defaults.apiKey(),
                defaults.model(),
                intOf(env.get("PLANNER_WAIT_DECISION_MS"), defaults.waitPerDecisionMs()),
                intOf(env.get("PLANNER_FIRST_WAIT_MS"), defaults.firstOneshotWaitMs()),
                intOf(env.get("PLANNER_WAIT_TURN_MS"), defaults.waitPerTurnMs()),
                intOf(env.get("PLANNER_MAX_CHECKPOINTS"), defaults.maxCheckpointsPerTurn()),
                doubleOf(env.get("PLANNER_SIMILARITY_THRESHOLD"), defaults.similarityThreshold()),
                intOf(env.get("ONESHOT_TIMEOUT_MS"), Math.min(defaults.oneshotTimeoutMs(), 10_000)),
                defaults.plannerCallTimeoutMs(),
                defaults.plannerCooldownMs(),
                defaults.cardRagPath(),
                defaults.oneshotMaxTokens(),
                // 默认 false：规划器只做场面分析/战术/combo 的自然语言建议，不产出可执行路径
                env.get("PLANNER_PATH_PLANNING") != null && truthy(env.get("PLANNER_PATH_PLANNING")),
                // 默认开：④升级竞速里规划会话参与抢答；0/false 关闭后升级只跑单轮直答
                env.get("PLANNER_RUSH_ANSWER") == null
                        ? defaults.plannerRushAnswer() : truthy(env.get("PLANNER_RUSH_ANSWER"))));
    }

    private static boolean truthy(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1")
                || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("yes"));
    }

    private static boolean falsy(String value) {
        return value != null && (value.equalsIgnoreCase("false") || value.equals("0")
                || value.equalsIgnoreCase("off") || value.equalsIgnoreCase("no"));
    }

    private static int intOf(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private PlanExecSettingsResolver() {
    }
}
