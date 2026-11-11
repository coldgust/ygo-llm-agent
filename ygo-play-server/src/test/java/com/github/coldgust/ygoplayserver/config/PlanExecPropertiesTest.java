package com.github.coldgust.ygoplayserver.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** server 侧 ygo.plan-exec 绑定 → PlannerSettings 的启用判断与默认级联。 */
class PlanExecPropertiesTest {

    @Test
    void v6DisabledWithoutApiKey() {
        assertFalse(new PlanExecProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).v6Enabled());
        assertTrue(new PlanExecProperties(null, "k", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).v6Enabled());
        assertFalse(new PlanExecProperties(false, "k", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).v6Enabled());
    }

    @Test
    void toSettingsFallsBackToCoreDefaults() {
        var settings = new PlanExecProperties(null, "k", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).toSettings();
        assertEquals("https://api.deepseek.com", settings.baseUrl());
        assertEquals("deepseek-chat", settings.model());
        assertEquals(4_000, settings.waitPerDecisionMs());
        assertEquals(10_000, settings.oneshotTimeoutMs());
    }

    @Test
    void toSettingsCarriesReasoningIntoPlannerSettings() {
        var settings = new PlanExecProperties(null, "k", null, null, null, "HIGH", "off",
                null, null, null, null, null, null, null, null, null).toSettings();
        assertEquals("high", settings.reasoningPlanner(), "归一化大小写");
        assertEquals("off", settings.reasoningDecision());
    }
}
