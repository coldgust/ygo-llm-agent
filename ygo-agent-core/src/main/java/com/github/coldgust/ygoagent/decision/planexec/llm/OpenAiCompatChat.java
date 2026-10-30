package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * OpenAI 兼容 /chat/completions 客户端（deepseek 等均兼容）。
 * <p>每次调用独立 HTTP 请求；持续 session 的历史由调用方（PlannerSession）维护并整段重发——
 * deepseek 服务端对相同前缀自动做 context caching，无需客户端处理。
 */
public final class OpenAiCompatChat implements ChatClient {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiCompatChat.class);

    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatChat(String baseUrl, String apiKey, String model) {
        String base = baseUrl == null ? "https://api.deepseek.com" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String complete(List<Msg> messages, ChatOptions options) {
        String text = attempt(messages, options, LlmTrace.nextId());
        if (text == null || text.isBlank()) {
            // 推理型模型（deepseek-flash 等）可能把 max_tokens 全花在 reasoning 上，正文为空。
            // 空响应等于这一轮白跑，放宽 token 上限再试一次（上限 8000，避免失控）。
            ChatOptions wider = new ChatOptions(options.temperature(),
                    Math.min(Math.max(options.maxTokens() * 2, 2_000), 8_000),
                    options.jsonMode(), options.timeout(), options.reasoning());
            log.warn("LLM 返回空内容（max_tokens={}），放宽到 {} 重试一次",
                    options.maxTokens(), wider.maxTokens());
            text = attempt(messages, wider, LlmTrace.nextId());
        }
        return text;
    }

    /** 单次尝试；400 且带了思考参数 → 端点可能不认这些字段，去掉重试；其他抖动直接再补一次。 */
    private String attempt(List<Msg> messages, ChatOptions options, long traceId) {
        try {
            return completeOnce(messages, options, traceId);
        } catch (ChatException e) {
            if (isTimeout(e)) {
                // 超时说明这次调用已经把预算花完了；再重试一次等于把等待翻倍
                // （实测深度复核 45s 超时 + 重试成功 → 59.8s，直接把回合时间吃光）。
                throw e;
            }
            if (options.reasoning() != null && e.getMessage() != null
                    && e.getMessage().contains("HTTP 400")) {
                log.warn("端点拒绝思考参数，改按默认模式重试: {}", truncate(e.getMessage()));
                return completeOnce(messages, options.withoutReasoning(), traceId);
            }
            return completeOnce(messages, options, traceId);
        }
    }

    private String completeOnce(List<Msg> messages, ChatOptions options, long traceId) {
        Map<String, Object> body = baseBody(wireMessages(messages), options);
        LlmTrace.request(traceId, model, options, messages);
        long start = System.nanoTime();
        JsonNode root;
        try {
            root = postJson(body, options.timeout());
        } catch (RuntimeException e) {
            LlmTrace.error(traceId, elapsedSince(start), e.getMessage());
            throw e;
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode content = choice.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            LlmTrace.error(traceId, elapsedSince(start), "响应缺少 content");
            throw new ChatException("LLM 响应缺少 content: " + truncate(root.toString()));
        }
        if (content.asText().isBlank()) {
            // 记下 finish_reason/用量，方便判断是"截断"还是"服务端空返回"
            log.warn("LLM 空正文：finish_reason={} usage={}",
                    choice.path("finish_reason").asText("?"),
                    root.path("usage").toString());
        }
        LlmTrace.response(traceId, elapsedSince(start), content.asText(),
                choice.path("finish_reason").asText(""));
        return content.asText();
    }

    /**
     * 带工具（function calling）的补全：模型先决定"要不要查规则"，需要就发 tool_call，
     * 我们执行检索把结果回填，再让它继续——整个回路对上层透明，最终仍返回一段文本
     * （规划器/one-shot 拿到的还是它们要的 JSON）。
     *
     * <p>两个刻意设计：
     * <ul>
     *   <li>工具轮 remove 掉 {@code response_format=json_object}：deepseek 对两者同时使用时
     *       会拒绝调用或让模型不再发 tool_calls，先放开让模型自由决定；</li>
     *   <li>任何异常都降级为普通 {@link #complete}：检索不可用不能拖垮决策。</li>
     * </ul>
     */
    @Override
    public String completeWithTools(List<Msg> messages, ChatOptions options,
                                    List<Tool> tools, ToolHandler handler) {
        if (tools == null || tools.isEmpty() || handler == null) {
            return complete(messages, options);
        }
        List<Map<String, Object>> wire = wireMessages(messages);
        long traceId = LlmTrace.nextId();
        try {
            int maxRounds = 6;
            for (int round = 0; round < maxRounds; round++) {
                Map<String, Object> body = baseBody(wire, options);
                body.remove("response_format"); // 与 tools 互斥，见方法注释
                body.put("tools", tools.stream().map(t -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of("name", t.name(),
                                "description", t.description(),
                                "parameters", t.parameters()))).toList());
                body.put("tool_choice", "auto");
                LlmTrace.toolRound(traceId, model, options, round + 1, maxRounds, wire,
                        tools.stream().map(Tool::name).toList());
                long start = System.nanoTime();
                JsonNode root = postJson(body, options.timeout());
                JsonNode message = root.path("choices").path(0).path("message");
                List<ToolCall> calls = parseToolCalls(message.path("tool_calls"));
                if (calls.isEmpty()) {
                    JsonNode content = message.path("content");
                    String text = content.isMissingNode() || content.isNull() ? "" : content.asText();
                    LlmTrace.response(traceId, elapsedSince(start), text, "tool-round-" + (round + 1));
                    return text;
                }
                log.info("模型请求查阅规则书（{} 次调用，第 {} 轮）", calls.size(), round + 1);
                wire.add(assistantWithToolCalls(message, calls));
                for (ToolCall call : calls) {
                    String result;
                    LlmTrace.toolCall(traceId, call.name(), call.arguments());
                    long toolStart = System.nanoTime();
                    try {
                        result = handler.execute(call);
                    } catch (RuntimeException e) {
                        // 检索失败也要把结果回填，让模型基于已有上下文继续，而不是整轮报废
                        result = "（规则检索失败：" + truncate(e.getMessage())
                                + "。请基于上下文里已有的规则速查与卡面信息继续判断）";
                    }
                    LlmTrace.toolResult(traceId, call.name(), elapsedSince(toolStart), result);
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", call.id());
                    toolMsg.put("content", result == null ? "" : result);
                    wire.add(toolMsg);
                }
            }
            // 轮次用尽：收掉工具，强制要正文
            Map<String, Object> body = baseBody(wire, options);
            if (options.jsonMode()) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            LlmTrace.toolRound(traceId, model, options, maxRounds + 1, maxRounds, wire, List.of());
            long start = System.nanoTime();
            JsonNode root = postJson(body, options.timeout());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() || content.isNull() ? "" : content.asText();
            LlmTrace.response(traceId, elapsedSince(start), text, "tool-rounds-exhausted");
            if (text.isBlank()) {
                // 实测：工具轮用尽后再要正文，模型有时会返回一段纯空白（像是"还在等工具结果"）。
                // 这时必须再要一次，否则上层拿到空串 → 整份建议/计划作废。
                log.warn("工具轮用尽后正文为空，补要一次（并明确禁止再调工具）");
                List<Map<String, Object>> retry = new ArrayList<>(wire);
                retry.add(Map.of("role", "user",
                        "content", "现在不要再调用任何工具：请直接基于上面已经查到的信息，输出最终 JSON。"));
                Map<String, Object> retryBody = baseBody(retry, options);
                if (options.jsonMode()) {
                    retryBody.put("response_format", Map.of("type", "json_object"));
                }
                JsonNode retryRoot = postJson(retryBody, options.timeout());
                JsonNode retryContent = retryRoot.path("choices").path(0).path("message").path("content");
                String retried = retryContent.isMissingNode() || retryContent.isNull()
                        ? "" : retryContent.asText();
                LlmTrace.response(traceId, elapsedSince(start), retried, "tool-rounds-retry");
                if (!retried.isBlank()) {
                    return retried;
                }
            }
            return text;
        } catch (RuntimeException e) {
            log.warn("带工具的调用失败，降级为普通补全: {}", truncate(e.getMessage()));
            LlmTrace.error(traceId, 0, "工具回路失败，降级为普通补全: " + e.getMessage());
            return complete(messages, options);
        }
    }

    private static long elapsedSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static List<Map<String, Object>> wireMessages(List<Msg> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Msg m : messages) {
            out.add(Map.of("role", m.role(), "content", m.content() == null ? "" : m.content()));
        }
        return out;
    }

    /** 请求体公共部分：model / messages / 采样参数 / 思考开关。 */
    private Map<String, Object> baseBody(List<Map<String, Object>> messages, ChatOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", options.temperature());
        body.put("max_tokens", options.maxTokens());
        if (options.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        // deepseek：thinking.type=disabled / reasoning_effort=none 即非思考模式（decision 默认走这条）。
        // 推理模式下 max_tokens 要把思维链算进去，稍不留神正文就是空的（实测 finish_reason=length）。
        if (options.reasoning() != null) {
            if ("off".equals(options.reasoning())) {
                body.put("thinking", Map.of("type", "disabled"));
                body.put("reasoning_effort", "none");
            } else {
                body.put("thinking", Map.of("type", "enabled"));
                body.put("reasoning_effort", options.reasoning());
            }
        }
        return body;
    }

    private JsonNode postJson(Map<String, Object> body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ChatException("LLM HTTP " + response.statusCode() + ": "
                        + truncate(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ChatException("LLM 调用 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatException("LLM 调用被中断", e);
        }
    }

    private static List<ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode fn = item.path("function");
            String name = fn.path("name").asText("");
            if (!name.isBlank()) {
                calls.add(new ToolCall(item.path("id").asText(""),
                        name, fn.path("arguments").asText("{}")));
            }
        }
        return calls;
    }

    private static Map<String, Object> assistantWithToolCalls(JsonNode message, List<ToolCall> calls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        JsonNode content = message.path("content");
        msg.put("content", content.isMissingNode() || content.isNull() ? "" : content.asText());
        msg.put("tool_calls", calls.stream().map(c -> Map.<String, Object>of(
                "id", c.id(),
                "type", "function",
                "function", Map.of("name", c.name(), "arguments", c.arguments()))).toList());
        return msg;
    }

    private static boolean isTimeout(ChatException e) {
        if (e.getCause() instanceof HttpTimeoutException) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("timed out") || msg.contains("timeout");
    }

    private static String truncate(String text) {
        return text == null ? "null" : text.substring(0, Math.min(300, text.length()));
    }
}
