package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 侧流水设置装配：环境相关项（地址/模型/卡库路径）必填缺失 → 抛异常点名键；
 * 调优旋钮不配 = 继承代码默认（PipelineSettings.defaults），配置了覆盖。
 * （docs/planner-judge-memory-redesign.md：路径规划/抢答/相似匹配 knob 已随顾问定位定稿删除。）
 */
class RuntimeConfigPlannerTest {

    /** 基础配置（仅环境相关必填键）；调优旋钮缺省 = 代码默认，用例按需覆盖。 */
    private static Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LLM_API_KEY", "k");
        env.put("LLM_BASE_URL", "https://api.deepseek.com");
        env.put("LLM_MODEL", "deepseek-chat");
        env.put("CARD_RAG_PATH", "data/card_rag/cards.jsonl");
        return env;
    }

    private static PipelineSettings planner(Map<String, String> overrides) {
        Map<String, String> env = baseEnv();
        env.putAll(overrides);
        return RuntimeConfig.planner(EnvSettings.of(env));
    }

    @Test
    void enabledByApiKeyPresence() {
        var settings = planner(Map.of());
        assertNotNull(settings);
        assertTrue(settings.enabled());
        assertEquals("k", settings.apiKey());
        assertEquals(4_000, settings.waitPerDecisionMs());
        assertEquals(30_000, settings.waitPerTurnMs());
        assertEquals(45_000, settings.plannerCallTimeoutMs());
    }

    @Test
    void explicitFalseDisables() {
        assertNull(planner(Map.of("PLAN_EXEC_ENABLED", "false")));
    }

    @Test
    void noKeyMeansDisabled() {
        assertNull(planner(Map.of("LLM_API_KEY", "")));
    }

    @Test
    void explicitEnabledWithoutKeyThrows() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> planner(Map.of("PLAN_EXEC_ENABLED", "true", "LLM_API_KEY", "")));
        assertTrue(e.getMessage().contains("LLM_API_KEY"), e.getMessage());
    }

    @Test
    void missingRequiredEnvKnobThrowsNamingKey() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> planner(Map.of("LLM_BASE_URL", "")));
        assertTrue(e.getMessage().contains("LLM_BASE_URL"), e.getMessage());
    }

    @Test
    void tuningKnobsDefaultToCodeDefaults() {
        var settings = planner(Map.of());
        assertEquals(4_000, settings.waitPerDecisionMs(), "等待预算默认在 PipelineSettings.defaults");
        assertEquals(45_000, settings.plannerCallTimeoutMs());
        assertEquals(60_000, settings.plannerCooldownMs());
    }

    @Test
    void malformedValueThrowsNamingKey() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> planner(Map.of("PLANNER_WAIT_DECISION_MS", "4,000")));
        assertTrue(e.getMessage().contains("PLANNER_WAIT_DECISION_MS"), e.getMessage());
    }

    @Test
    void budgetsOverridable() {
        var settings = planner(Map.of(
                "PLANNER_WAIT_DECISION_MS", "3000",
                "PLANNER_WAIT_TURN_MS", "15000",
                "PLANNER_FIRST_WAIT_MS", "9000",
                "LLM_BASE_URL", "https://example.invalid",
                "LLM_MODEL", "test-model",
                "CARD_RAG_PATH", "/tmp/cards.jsonl"));
        assertEquals(3_000, settings.waitPerDecisionMs());
        assertEquals(15_000, settings.waitPerTurnMs());
        assertEquals(9_000, settings.firstAdviceWaitMs());
        assertEquals("https://example.invalid", settings.baseUrl());
        assertEquals("test-model", settings.model());
        assertEquals("/tmp/cards.jsonl", settings.cardRagPath().toString());
    }

    @Test
    void plannerModelOverridesLlmModel() {
        var settings = planner(Map.of("PLANNER_MODEL", "deepseek-reasoner"));
        assertEquals("deepseek-reasoner", settings.model(),
                "PLANNER_MODEL 优先于 LLM_MODEL（决策热路径与叙事分模型）");
    }

    @Test
    void waitTurnClampedBelowWaitDecision() {
        var settings = planner(Map.of(
                "PLANNER_WAIT_DECISION_MS", "5000",
                "PLANNER_WAIT_TURN_MS", "1000"));
        // 回合预算不得小于单次预算（record 紧凑构造收敛）
        assertEquals(5_000, settings.waitPerTurnMs());
    }

    @Test
    void waitDecisionZeroForcesFirstWaitOff() {
        var settings = planner(Map.of("PLANNER_WAIT_DECISION_MS", "0"));
        assertEquals(0, settings.firstAdviceWaitMs(), "显式关闭等待时首等也归零");
    }

    @Test
    void reasoningNormalizedPlannerSide() {
        var settings = planner(Map.of(
                "LLM_REASONING_PLANNER", "HIGH",
                "LLM_REASONING_DECISION", "low"));
        assertEquals("high", settings.reasoningPlanner(), "大小写不敏感");
        assertEquals("low", settings.reasoningDecision());

        // 非法值 = 未配置（档位默认：checkpoint 统一 low、决策 off）
        var fallback = planner(Map.of(
                "LLM_REASONING_PLANNER", "max",
                "LLM_REASONING_DECISION", "medium!"));
        assertNull(fallback.reasoningPlanner());
        assertNull(fallback.reasoningDecision());
    }
}
