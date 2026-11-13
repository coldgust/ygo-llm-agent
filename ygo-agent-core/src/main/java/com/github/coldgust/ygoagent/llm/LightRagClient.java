package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.support.Texts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * LightRAG {@code /query/data} 的共享查询客户端（检索工具与 ContentRetriever 共用）。
 * 只取证据（chunk 原文），不做二次 LLM 生成；服务不可用抛 IllegalStateException，
 * 由调用方决定降级方式（工具回填错误文案 / retriever 返回空）。
 */
public final class LightRagClient {

    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String mode;

    public LightRagClient(LightRagSettings lightRag, String mode) {
        this.endpoint = lightRag.url() + "/query/data";
        this.apiKey = lightRag.apiKey();
        this.timeout = Duration.ofMillis(lightRag.timeoutMillis());
        this.mode = mode == null || mode.isBlank() ? "mix" : mode;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** 执行一次检索；{@code include_chunk_content=true} 保留原文。 */
    public JsonNode query(String query, int topK) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("query", query);
        payload.put("mode", mode);
        payload.put("top_k", topK);
        payload.put("chunk_top_k", topK);
        payload.put("include_chunk_content", true);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("LightRAG HTTP " + response.statusCode() + ": "
                        + Texts.flattenClip(response.body(), 200));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("LightRAG 检索 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LightRAG 检索被中断", e);
        }
    }
}
