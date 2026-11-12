package com.github.coldgust.ygoagent.llm;

import java.util.Locale;

/**
 * 全链路日志设置（{@code YGO_VERBOSE_LOG} / {@code YGO_VERBOSE_MAX_CHARS} 的类型化形态）。
 *
 * @param enabled  是否输出全链路日志（LLM 请求/响应、工具调用、决策流水）；默认关
 * @param maxChars 单条日志的截断上限；0/负数 = 不截断
 */
public record VerboseLogSettings(boolean enabled, int maxChars) {

    public static VerboseLogSettings off() {
        return new VerboseLogSettings(false, 0);
    }

    /** CLI 从 env+dotenv 合并解析、server 从 Spring 取值后传入原始串；null/空白 = 沿用默认（关/不截断）。 */
    public static VerboseLogSettings parse(String enabledRaw, String maxCharsRaw) {
        return new VerboseLogSettings(parseEnabled(enabledRaw), parseMaxChars(maxCharsRaw));
    }

    private static boolean parseEnabled(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !"false".equals(v) && !"0".equals(v) && !"off".equals(v) && !"no".equals(v);
    }

    private static int parseMaxChars(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0 : Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
