package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 档位 → wire 参数的映射（deepseek 私有参数在这里）：json/tools 配对、thinking 开关、重建。 */
class TierParametersTest {

    @Test
    void jsonMode_不带工具时生效() {
        OpenAiChatRequestParameters p = TierParameters.of(
                new ChatOptions(0.2, 500, true, null), "deepseek-chat", false);
        assertEquals(ResponseFormat.JSON, p.responseFormat());
        assertEquals(500, p.maxOutputTokens());
        assertNull(p.reasoningEffort());
        assertTrue(p.customParameters() == null || p.customParameters().isEmpty(),
                "未配置思考时不带 thinking 参数");
    }

    @Test
    void jsonMode_带工具时不带responseFormat() {
        // 实测（pitfalls §7）：json_object 会非确定性抑制 deepseek 的结构化 tool_calls
        OpenAiChatRequestParameters p = TierParameters.of(
                new ChatOptions(0.2, 500, true, null), "deepseek-chat", true);
        assertNull(p.responseFormat(), "带工具的调用不带 json mode（由框架追加的 schema 指令保证 JSON 遵循度）");
        assertTrue(p.toolSpecifications().isEmpty(), "工具 spec 由 AiServices 生成，档位参数不带");
    }

    @Test
    void 思考档off_thinking_disabled与effort_none() {
        OpenAiChatRequestParameters p = TierParameters.of(
                ChatOptions.decision(100), "deepseek-chat", false);
        assertEquals("none", p.reasoningEffort());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), p.customParameters());
    }

    @Test
    void 思考档low_thinking_enabled与effort() {
        OpenAiChatRequestParameters p = TierParameters.of(
                new ChatOptions(0.4, 8000, true, "low"), "deepseek-chat", false);
        assertEquals("low", p.reasoningEffort());
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), p.customParameters());
    }
}
