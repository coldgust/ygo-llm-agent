package com.github.coldgust.ygoagent.decision.planexec;

import tools.jackson.databind.JsonNode;

/** 提示词与检索结果的通用文本处理（压平截断 / 取第一个非空白值）。 */
public final class Texts {

    /** 压平全部空白（含换行）后按限长截断，超长补 "…"；null 按空串。 */
    public static String flattenClip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 依次取第一个非空白值；全空白返回 null。 */
    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 依次取 JSON 对象里第一个非空白字段值；全缺失返回空串。 */
    public static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Texts() {
    }
}
