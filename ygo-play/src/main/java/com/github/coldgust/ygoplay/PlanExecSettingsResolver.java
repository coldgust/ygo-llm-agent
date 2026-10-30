package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.planexec.PlannerSettings;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTuning;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * v6 Planner–Executor 的配置解析：CLI 之外的 env/dotenv 入口（与 RuntimeConfig 同风格）。
 *
 * <pre>
 * PLAN_EXEC_ENABLED           总开关（默认：LLM_API_KEY 存在即开）
 * PLANNER_WAIT_DECISION_MS    决策时一次最多等待规划器（默认 4000）
 * PLANNER_FIRST_WAIT_MS       本回合首次 one-shot 前等计划的上限（默认 7000）
 * PLANNER_WAIT_TURN_MS        一回合内等待累计上限（默认 20000）
 * PLANNER_MAX_CHECKPOINTS     每回合后台 checkpoint 上限（默认 6）
 * PLANNER_SIMILARITY_THRESHOLD 相似匹配阈值（默认 0.80）
 * ONESHOT_TIMEOUT_MS          one-shot 硬超时（默认 5000）
 * PLANNER_RUSH_ANSWER         ④升级竞速里规划器抢答的开关（默认开；0/false 关闭）
 * CARD_RAG_PATH               card_rag cards.jsonl（默认 data/card_rag/cards.jsonl）
 * LLM_API_KEY / LLM_BASE_URL / LLM_MODEL   与 RuntimeConfig 共用
 * </pre>
 */
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
