package com.github.coldgust.ygoplay;

import java.time.Duration;

/**
 * LightRAG 知识栈连接（规则检索 / 历史对局检索共用）。
 * 仅作为 {@link DuelRunner} 门面的参数类型：server 由 Spring 配置绑定后构造，CLI 由 RuntimeConfig 解析。
 *
 * @param url           LightRAG 服务地址（默认 http://localhost:9621）
 * @param apiKey        API key：LightRAG 开启 AUTH_ACCOUNTS 后必填；留空 = 不带认证
 * @param timeoutMillis 单次检索超时 ms（下限 1000，默认 8000）
 */
public record LightRagEndpoint(String url, String apiKey, long timeoutMillis) {

    public LightRagEndpoint {
        url = url == null || url.isBlank() ? "http://localhost:9621" : url.trim();
        apiKey = apiKey == null ? "" : apiKey;
        timeoutMillis = Math.max(1_000L, timeoutMillis);
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMillis);
    }
}
