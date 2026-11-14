package com.github.coldgust.ygoagent.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子式 Builder 语义：未设置的字段继承种子（默认值唯一定义在 defaults）；
 * build() 走 canonical 构造器，归一化与跨字段耦合对 Builder 产物同样生效。
 */
class PipelineSettingsBuilderTest {

    private PipelineSettings seed() {
        return PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
    }

    @Test
    void unsetFieldsInheritTheSeed() {
        PipelineSettings s = PipelineSettings.builder(seed())
                .waitPerDecisionMs(500)
                .build();
        assertEquals(500, s.waitPerDecisionMs());
        assertEquals("https://api.deepseek.com", s.baseUrl());
        assertEquals("test-key", s.apiKey());
        assertEquals("deepseek-chat", s.model());
        assertEquals(7_000, s.firstJudgeWaitMs());
        assertEquals(30_000, s.waitPerTurnMs());
        assertEquals(6, s.maxCheckpointsPerTurn());
        assertEquals(0.80, s.similarityThreshold());
        assertEquals(10_000, s.judgeTimeoutMs());
    }

    @Test
    void overridesWinOverTheSeed() {
        PipelineSettings s = PipelineSettings.builder(seed())
                .waitPerDecisionMs(0)
                .plannerRushAnswer(false)
                .pathPlanning(true)
                .build();
        assertEquals(0, s.waitPerDecisionMs());
        assertFalse(s.plannerRushAnswer());
        assertTrue(s.pathPlanning());
    }

    @Test
    void normalizationAndCrossFieldCouplingStillApplyAfterBuild() {
        // waitPerDecisionMs=0 视为"完全不等待"：firstJudgeWaitMs 强制归零，即使种子是 7_000
        PipelineSettings s = PipelineSettings.builder(seed())
                .waitPerDecisionMs(0)
                .build();
        assertEquals(0, s.firstJudgeWaitMs());
        // waitPerTurnMs 不低于 waitPerDecisionMs
        PipelineSettings t = PipelineSettings.builder(seed())
                .waitPerDecisionMs(40_000)
                .waitPerTurnMs(1_000)
                .build();
        assertEquals(40_000, t.waitPerTurnMs());
        // 思考强度归一化：非法值 → 档位默认（null），大小写/空白容忍
        assertNull(PipelineSettings.builder(seed()).reasoningPlanner("  WAT ").build().reasoningPlanner());
        assertEquals("medium",
                PipelineSettings.builder(seed()).reasoningPlanner(" Medium ").build().reasoningPlanner());
    }
}
