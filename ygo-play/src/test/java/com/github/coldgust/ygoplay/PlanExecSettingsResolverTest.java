package com.github.coldgust.ygoplay;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanExecSettingsResolverTest {

    @Test
    void enabledByKeyPresence() {
        var settings = PlanExecSettingsResolver.resolve(Map.of("LLM_API_KEY", "k")).orElseThrow();
        assertTrue(settings.enabled());
        assertEquals("k", settings.apiKey());
        assertEquals(4_000, settings.waitPerDecisionMs());
        assertEquals(30_000, settings.waitPerTurnMs());
        assertEquals(0.80, settings.similarityThreshold(), 1e-9);
    }

    @Test
    void explicitFalseDisables() {
        assertFalse(PlanExecSettingsResolver.resolve(
                Map.of("LLM_API_KEY", "k", "PLAN_EXEC_ENABLED", "false")).isPresent());
    }

    @Test
    void noKeyMeansDisabled() {
        assertFalse(PlanExecSettingsResolver.resolve(Map.of()).isPresent());
    }

    @Test
    void budgetsAndThresholdOverridable() {
        var settings = PlanExecSettingsResolver.resolve(Map.of(
                "LLM_API_KEY", "k",
                "PLANNER_WAIT_DECISION_MS", "3000",
                "PLANNER_WAIT_TURN_MS", "15000",
                "PLANNER_SIMILARITY_THRESHOLD", "0.9",
                "LLM_BASE_URL", "https://example.invalid",
                "LLM_MODEL", "test-model",
                "CARD_RAG_PATH", "/tmp/cards.jsonl")).orElseThrow();
        assertEquals(3_000, settings.waitPerDecisionMs());
        assertEquals(15_000, settings.waitPerTurnMs());
        assertEquals(0.9, settings.similarityThreshold(), 1e-9);
        assertEquals("https://example.invalid", settings.baseUrl());
        assertEquals("test-model", settings.model());
        assertEquals("/tmp/cards.jsonl", settings.cardRagPath().toString());
    }

    @Test
    void waitTurnClampedBelowWaitDecision() {
        var settings = PlanExecSettingsResolver.resolve(Map.of(
                "LLM_API_KEY", "k",
                "PLANNER_WAIT_DECISION_MS", "5000",
                "PLANNER_WAIT_TURN_MS", "1000")).orElseThrow();
        // 回合预算不得小于单次预算（record 紧凑构造收敛）
        assertEquals(5_000, settings.waitPerTurnMs());
    }

    @Test
    void rushAnswerOnByDefaultAndDisablable() {
        var settings = PlanExecSettingsResolver.resolve(Map.of("LLM_API_KEY", "k")).orElseThrow();
        assertTrue(settings.plannerRushAnswer(), "抢答默认开");

        var off = PlanExecSettingsResolver.resolve(Map.of(
                "LLM_API_KEY", "k", "PLANNER_RUSH_ANSWER", "false")).orElseThrow();
        assertFalse(off.plannerRushAnswer(), "0/false 关闭抢答");

        var one = PlanExecSettingsResolver.resolve(Map.of(
                "LLM_API_KEY", "k", "PLANNER_RUSH_ANSWER", "1")).orElseThrow();
        assertTrue(one.plannerRushAnswer());
    }
}
