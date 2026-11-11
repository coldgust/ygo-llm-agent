package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.planexec.PlannerSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI 侧 v6 设置级联：覆盖值与 core 默认的合并规则（查表注入，可确定性测）。 */
class RuntimeConfigPlannerTest {

    /** 生产查表来自 OS env 优先、.env 并入；测试用纯 map 等价替代。 */
    private static PlannerSettings planner(Map<String, String> values) {
        return RuntimeConfig.planner(values::get).orElseThrow();
    }

    @Test
    void enabledByKeyPresence() {
        var settings = planner(Map.of("LLM_API_KEY", "k"));
        assertTrue(settings.enabled());
        assertEquals("k", settings.apiKey());
        assertEquals(4_000, settings.waitPerDecisionMs());
        assertEquals(30_000, settings.waitPerTurnMs());
        assertEquals(0.80, settings.similarityThreshold(), 1e-9);
    }

    @Test
    void explicitFalseDisables() {
        assertFalse(RuntimeConfig.planner(
                Map.<String, String>of("LLM_API_KEY", "k", "PLAN_EXEC_ENABLED", "false")::get).isPresent());
    }

    @Test
    void noKeyMeansDisabled() {
        assertFalse(RuntimeConfig.planner(Map.<String, String>of()::get).isPresent());
    }

    @Test
    void budgetsAndThresholdOverridable() {
        var settings = planner(Map.of(
                "LLM_API_KEY", "k",
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
    void waitTurnClampedBelowWaitDecision() {
        var settings = planner(Map.of(
                "LLM_API_KEY", "k",
                "PLANNER_WAIT_DECISION_MS", "5000",
                "PLANNER_WAIT_TURN_MS", "1000"));
        // 回合预算不得小于单次预算（record 紧凑构造收敛）
        assertEquals(5_000, settings.waitPerTurnMs());
    }

    @Test
    void rushAnswerOnByDefaultAndDisablable() {
        assertTrue(planner(Map.of("LLM_API_KEY", "k")).plannerRushAnswer(), "抢答默认开");

        assertFalse(planner(Map.of(
                "LLM_API_KEY", "k", "PLANNER_RUSH_ANSWER", "false")).plannerRushAnswer(), "0/false 关闭抢答");

        assertTrue(planner(Map.of(
                "LLM_API_KEY", "k", "PLANNER_RUSH_ANSWER", "1")).plannerRushAnswer());
    }

    @Test
    void reasoningNormalizedPlannerSide() {
        var settings = planner(Map.of(
                "LLM_API_KEY", "k",
                "LLM_REASONING_PLANNER", "HIGH",
                "LLM_REASONING_DECISION", "low"));
        assertEquals("high", settings.reasoningPlanner(), "大小写不敏感");
        assertEquals("low", settings.reasoningDecision());

        // 非法值 = 未配置（档位默认：checkpoint 统一 low、决策 off）
        var fallback = planner(Map.of(
                "LLM_API_KEY", "k",
                "LLM_REASONING_PLANNER", "max",
                "LLM_REASONING_DECISION", "medium!"));
        assertNull(fallback.reasoningPlanner());
        assertNull(fallback.reasoningDecision());
    }
}
