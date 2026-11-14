package com.github.coldgust.ygoplayserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ygo.plan-exec 绑定（键用全限定名）：非 String 组件的空串绑定为 null = 继承 core 代码默认；
 * 环境相关项缺失/留空 → toSettings 抛异常点名。 */
class PlanExecPropertiesTest {

    /** 基础配置（镜像 application.yaml 的 plan-exec 桥接；调优旋钮 null = 继承 core 默认）。 */
    private static PlanExecProperties props(Boolean enabled, String apiKey) {
        return new PlanExecProperties(enabled, apiKey, "https://api.deepseek.com", null, "deepseek-chat",
                null, null, "data/card_rag/cards.jsonl",
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void v6DisabledWithoutApiKey() {
        assertFalse(props(null, null).v6Enabled());
        assertTrue(props(null, "k").v6Enabled());
        assertFalse(props(false, "k").v6Enabled());
    }

    @Test
    void explicitEnabledWithoutKeyThrows() {
        assertThrows(IllegalStateException.class, () -> props(true, null).v6Enabled());
    }

    @Test
    void toSettingsInheritsCodeDefaultsForTuningKnobs() {
        var settings = props(null, "k").toSettings();
        assertEquals("https://api.deepseek.com", settings.baseUrl());
        assertEquals("deepseek-chat", settings.model());
        assertEquals(4_000, settings.waitPerDecisionMs(), "等待预算默认在 PipelineSettings.defaults");
        assertEquals(10_000, settings.judgeTimeoutMs());
        assertEquals(45_000, settings.plannerCallTimeoutMs());
        assertEquals(60_000, settings.plannerCooldownMs());
        assertEquals(3_000, settings.judgeMaxTokens());
        assertFalse(settings.pathPlanning());
        assertTrue(settings.plannerRushAnswer());
    }

    @Test
    void toSettingsOverridesTuningKnobsWhenConfigured() {
        var settings = new PlanExecProperties(null, "k", "https://api.deepseek.com", null, "deepseek-chat",
                "HIGH", "off", "data/card_rag/cards.jsonl",
                3_000, null, null, null, 0.9, null, null, null, null, true, null).toSettings();
        assertEquals(3_000, settings.waitPerDecisionMs(), "配置了的调优旋钮覆盖默认");
        assertEquals(0.9, settings.similarityThreshold(), 1e-9);
        assertTrue(settings.pathPlanning());
        assertEquals("high", settings.reasoningPlanner(), "归一化大小写");
        assertEquals("off", settings.reasoningDecision());
    }

    @Test
    void missingRequiredEnvKnobThrowsNamingKey() {
        var props = new PlanExecProperties(null, "k", "", "deepseek-chat", null,
                null, null, "data/card_rag/cards.jsonl",
                null, null, null, null, null, null, null, null, null, null, null);
        Exception e = assertThrows(IllegalStateException.class, props::toSettings);
        assertTrue(e.getMessage().contains("base-url"), e.getMessage());
    }

    @Test
    void emptyBridgesBindToNullThenInheritDefaults() {
        // application.yaml 的调优桥接是 ${ENV:}（空默认）：env 未配置时值为空串，
        // 非 String 组件的空串绑定为 null = 继承 core 代码默认（String 组件绑定为 ""，由归一化收编）。
        // 注意：MapConfigurationPropertySource 的键必须是全限定属性名（ygo.plan-exec.*）。
        Map<String, String> values = new HashMap<>();
        values.put("ygo.plan-exec.base-url", "https://api.deepseek.com");
        values.put("ygo.plan-exec.api-key", "k");
        values.put("ygo.plan-exec.planner-model", "");
        values.put("ygo.plan-exec.llm-model", "deepseek-chat");
        values.put("ygo.plan-exec.card-rag-path", "data/card_rag/cards.jsonl");
        values.put("ygo.plan-exec.reasoning-planner", "");
        values.put("ygo.plan-exec.reasoning-decision", "");
        values.put("ygo.plan-exec.wait-decision-ms", "");
        values.put("ygo.plan-exec.path-planning", "");
        values.put("ygo.plan-exec.rush-answer", "");
        PlanExecProperties props = new Binder(new MapConfigurationPropertySource(values))
                .bind("ygo.plan-exec", Bindable.of(PlanExecProperties.class)).get();
        assertNull(props.waitDecisionMs(), "Integer 空串绑定为 null");
        assertNull(props.pathPlanning());
        assertNull(props.rushAnswer());
        var settings = props.toSettings();
        assertEquals(4_000, settings.waitPerDecisionMs(), "继承 core 调优默认");
        assertFalse(settings.pathPlanning());
        assertTrue(settings.plannerRushAnswer());
    }

    @Test
    void toStringMasksApiKey() {
        assertFalse(props(null, "secret").toString().contains("secret"), "apiKey 不进 toString");
    }
}
