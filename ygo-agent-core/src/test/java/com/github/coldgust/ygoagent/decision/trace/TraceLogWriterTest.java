package com.github.coldgust.ygoagent.decision.trace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件日志的格式化：要一行能扫完，且不能把长载荷原样刷屏。
 */
class TraceLogWriterTest {

    @Test
    void payloadIsFormattedAsReadableKeyValue() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", 31);
        payload.put("turn", 3);
        payload.put("fallback", false);
        payload.put("submitted", "发动 交闪(25048511)我方手牌");

        String out = TraceLogWriter.format(payload);

        assertEquals("{seq=31, turn=3, fallback=false, submitted=发动 交闪(25048511)我方手牌}", out);
    }

    @Test
    void longValuesAndCollectionsAreCompressed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missReasons", "计划步未接地 | 无分支相似命中（阈值 0.8）".repeat(20));
        payload.put("stepDetails", List.of(1, 2, 3));
        payload.put("missing", null);

        String out = TraceLogWriter.format(payload);

        assertTrue(out.contains("stepDetails=[3 项]"), out);
        assertTrue(out.contains("missing=null"), out);
        assertTrue(out.length() < 220, "长文本要截断，别把日志刷爆：" + out.length());
    }
}
