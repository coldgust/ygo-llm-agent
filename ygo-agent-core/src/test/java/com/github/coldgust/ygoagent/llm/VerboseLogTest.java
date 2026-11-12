package com.github.coldgust.ygoagent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路日志的纯函数部分（开关关闭时也必须安全：这些方法在每个决策点都会被调用）。
 */
class VerboseLogTest {

    private final VerboseLog log = new VerboseLog(VerboseLogSettings.off());

    @Test
    void previewFlattensAndCaps() {
        assertEquals("a b c", log.preview("a\nb\tc", 20));
        assertEquals("", log.preview(null, 20));

        String longText = "字".repeat(200);
        String preview = log.preview(longText, 50);
        assertEquals(51, preview.length(), "50 字 + 省略号");
        assertEquals("…", preview.substring(50));
    }

    @Test
    void scopeIsSafeWhenDisabled() {
        // 默认关闭：scope 返回空实现，close 不能抛；也不能把线程上下文弄脏
        assertDoesNotThrow(() -> {
            try (var scope = log.scope("决策器判断")) {
                log.decision("① 唯一合法", "ChainDecline");
                log.step("④ 决策器判断", 812, "决策器判断");
            }
        });
        assertDoesNotThrow(() -> log.toolCall(1, "find_card", "{}"));
        assertDoesNotThrow(() -> log.toolResult(1, "find_card", 12, "结果"));
        assertDoesNotThrow(() -> log.response(1, 10, "", ""));
        assertFalse(log.enabled());
    }

    @Test
    void settingsParseKeepsLegacySemantics() {
        // null/空白 = 关；true/1/on/yes = 开；其余 = 关
        assertFalse(VerboseLogSettings.parse(null, null).enabled());
        assertFalse(VerboseLogSettings.parse("", "  ").enabled());
        assertTrue(VerboseLogSettings.parse("true", "500").enabled());
        assertTrue(VerboseLogSettings.parse("1", "").enabled());
        assertTrue(VerboseLogSettings.parse("on", "").enabled());
        assertFalse(VerboseLogSettings.parse("off", "").enabled());
        assertFalse(VerboseLogSettings.parse("0", "").enabled());
        assertFalse(VerboseLogSettings.parse("no", "").enabled());
        // 非 false/0/off/no 的其余值沿用旧语义：视为开启（fail-open，实机调参场景少一次排查）
        assertTrue(VerboseLogSettings.parse("乱写", "").enabled());
        assertEquals(500, VerboseLogSettings.parse("true", "500").maxChars());
        assertEquals(0, VerboseLogSettings.parse("true", "abc").maxChars());
        assertEquals(0, VerboseLogSettings.parse("true", "-3").maxChars());
    }
}
