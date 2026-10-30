package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全链路日志的纯函数部分（开关关闭时也必须安全：这些方法在每个决策点都会被调用）。
 */
class LlmTraceTest {

    @Test
    void previewFlattensAndCaps() {
        assertEquals("a b c", LlmTrace.preview("a\nb\tc", 20));
        assertEquals("", LlmTrace.preview(null, 20));

        String longText = "字".repeat(200);
        String preview = LlmTrace.preview(longText, 50);
        assertEquals(51, preview.length(), "50 字 + 省略号");
        assertEquals("…", preview.substring(50));
    }

    @Test
    void scopeIsSafeWhenDisabled() {
        // 默认关闭：scope 返回空实现，close 不能抛；也不能把线程上下文弄脏
        assertDoesNotThrow(() -> {
            try (var scope = LlmTrace.scope("决策器判断")) {
                LlmTrace.decision("① 唯一合法", "ChainDecline");
                LlmTrace.step("④ 决策器判断", 812, "决策器判断");
            }
        });
        assertDoesNotThrow(() -> LlmTrace.toolCall(1, "find_card", "{}"));
        assertDoesNotThrow(() -> LlmTrace.toolResult(1, "find_card", 12, "结果"));
        assertDoesNotThrow(() -> LlmTrace.response(1, 10, "", ""));
    }
}
