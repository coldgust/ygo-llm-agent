package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 侧 v6 设置装配：环境相关项（地址/模型/卡库路径）必填缺失 → 抛异常点名键；
 * 调优旋钮不配 = 继承代码默认（PipelineSettings.defaults），配置了覆盖。
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
        assertEquals(0.80, settings.similarityThreshold(), 1e-9);
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
        assertFalse(settings.pathPlanning(), "顾问模式默认");
        assertTrue(settings.plannerRushAnswer(), "抢答默认开");
    }

    @Test
    void malformedValueThrowsNamingKey() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> planner(Map.of("PLANNER_SIMILARITY_THRESHOLD", "0,8")));
        assertTrue(e.getMessage().contains("PLANNER_SIMILARITY_THRESHOLD"), e.getMessage());
    }

    @Test
    void budgetsAndThresholdOverridable() {
        var settings = planner(Map.of(
                "PLANNER_WAIT_DECISION_MS", "3000",
                "PLANNER_WAIT_TURN_MS", "15000",
                "PLANNER_SIMILARITY_THRESHOLD", "0.9",
                "LLM_BASE_URL", "https://example.invalid",
                "LLM_MODEL", "test-model",
                "CARD_RAG_PATH", "/tmp/cards.jsonl"));
        assertEquals(3_000, settings.waitPerDecisionMs());
        assertEquals(15_000, settings.waitPerTurnMs());
        assertEquals(0.9, settings.similarityThreshold(), 1e-9);
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
    void rushAnswerAndPathPlanningFromEnv() {
        assertFalse(planner(Map.of("PLANNER_RUSH_ANSWER", "false")).plannerRushAnswer(), "0/false 关闭抢答");
        assertTrue(planner(Map.of("PLANNER_RUSH_ANSWER", "1")).plannerRushAnswer());
        assertTrue(planner(Map.of("PLANNER_PATH_PLANNING", "true")).pathPlanning());
        assertFalse(planner(Map.of()).pathPlanning(), "未配置 = 顾问模式默认");
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
