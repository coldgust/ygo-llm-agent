package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 思考强度配置项（LLM_REASONING_PLANNER / LLM_REASONING_DECISION）。 */
class LlmTuningTest {

    @Test
    void readsBothLevelsCaseInsensitively() {
        LlmTuning tuning = LlmTuning.of(Map.of(
                LlmTuning.PLANNER_KEY, "Low",
                LlmTuning.DECISION_KEY, " OFF "));

        assertEquals("low", tuning.planner());
        assertEquals("off", tuning.decision());
    }

    @Test
    void blankOrUnknownLevelsMeanUseCallDefault() {
        // 拼错一个词不该让整局跑不起来：无法识别时等于"不覆盖"，沿用调用档位的默认值。
        assertNull(LlmTuning.of(Map.of()).planner());
        assertNull(LlmTuning.of(Map.of(LlmTuning.PLANNER_KEY, "")).planner());
        assertNull(LlmTuning.of(Map.of(LlmTuning.PLANNER_KEY, "med")).planner());
        assertNull(LlmTuning.of(Map.of(LlmTuning.DECISION_KEY, "high2")).decision());
    }

    @Test
    void enablingThinkingRaisesTheTokenBudget() {
        // deepseek 把思维链算进 max_tokens：预算给小了会 finish_reason=length、正文为空。
        ChatClient.ChatOptions base = ChatClient.ChatOptions.decision(800, Duration.ofSeconds(5));

        ChatClient.ChatOptions thinking = base.withReasoning("low");

        assertEquals("low", thinking.reasoning());
        assertEquals(4_000, thinking.maxTokens(), "开思考时预算至少抬到 4000");
        assertEquals(base.temperature(), thinking.temperature(), "其余参数不变");
        assertEquals(base.timeout(), thinking.timeout());
    }

    @Test
    void turningThinkingOffKeepsCallDefaults() {
        ChatClient.ChatOptions base = ChatClient.ChatOptions.decision(800, Duration.ofSeconds(5));

        ChatClient.ChatOptions same = base.withReasoning("off");
        assertEquals(800, same.maxTokens(), "关思考不需要抬预算");

        ChatClient.ChatOptions untouched = base.withReasoning(null);
        assertEquals(800, untouched.maxTokens());
        assertEquals("off", untouched.reasoning());
    }
}
