package com.github.coldgust.ygoagent.llm;

/**
 * LightRAG 知识栈连接参数（规则检索 / 历史对局检索 / ContentRetriever 共用）。
 * url 与 timeout 必填（缺了就地抛异常提示配置项，不在传输层兜默认值）；
 * 整个对象为 null = 宿主不启用检索（工具不注册），这是"检索不可用"的唯一表达方式。
 */
public record LightRagSettings(String url, String apiKey, long timeoutMillis) {

    public LightRagSettings {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("LightRAG url 未配置"
                    + "（CLI：.env 的 LIGHTRAG_URL；server：application.yaml 的 ygo.lightrag.url）；"
                    + "不需要检索时传 null（工具不注册），不要传空地址");
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("LightRAG timeout 必须为正数"
                    + "（CLI：.env 的 LIGHTRAG_TIMEOUT_MS；server：application.yaml 的 ygo.lightrag.timeout-ms）");
        }
        apiKey = apiKey == null ? "" : apiKey;
    }
}
