package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LightRAG 知识栈连接（规则检索 / 历史对局检索共用）。
 * 键来自 .env / 环境变量，经 application.yaml 的 {@code ${LIGHTRAG_*:默认}} 桥接进入 ygo.lightrag。
 *
 * @param url          LightRAG 服务地址（默认 http://localhost:9621）
 * @param apiKey       API key：LightRAG 开启 AUTH_ACCOUNTS 后必填；留空 = 不带认证
 * @param timeoutMs    单次检索超时 ms（下限 1000，默认 8000）
 */
@ConfigurationProperties(prefix = "ygo.lightrag")
public record LightRagProperties(String url, String apiKey, long timeoutMs) {

    public LightRagProperties {
        url = url == null || url.isBlank() ? "http://localhost:9621" : url.trim();
        apiKey = apiKey == null ? "" : apiKey;
        timeoutMs = Math.max(1_000L, timeoutMs);
    }
}
