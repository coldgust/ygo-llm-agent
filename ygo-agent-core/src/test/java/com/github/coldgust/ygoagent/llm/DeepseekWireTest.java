package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * deepseek wire 语义钉子（本地 stub HTTP 服务器，走完整装饰器栈 + 真 OpenAiChatModel）：
 * 档位参数真的落到请求体——thinking 私有参数拍平进顶层、reasoning_effort、json mode。
 */
class DeepseekWireTest {

    private StubLlm stub;

    private ChatModel factory() throws IOException {
        stub = new StubLlm();
        return AgentModels.openAi(
                new LlmEndpoint(stub.url(), "test-key", "deepseek-chat"),
                ChatOptions.decision(100), Duration.ofSeconds(5), false,
                new VerboseLog(VerboseLogSettings.off()));
    }

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    void 决策档_请求体带json与thinking_disabled() throws IOException {
        ChatModel agents = factory();
        stub.enqueue(200, StubLlm.content("ok"));
        var options = ChatOptions.decision(100);

        // 档位参数 bake 进模型：请求不带 parameters，由 defaultRequestParameters 供给
        agents.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("q")))
                .build());

        JsonNode body = stub.bodies.getFirst();
        assertEquals("deepseek-chat", body.path("model").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("none", body.path("reasoning_effort").asText());
        assertEquals("/chat/completions", stub.paths.getFirst());
    }

    @Test
    void 推理档_请求体带thinking_enabled与effort() throws IOException {
        stub = new StubLlm();
        stub.enqueue(200, StubLlm.content("ok"));
        var options = new ChatOptions(0.4, 8000, true, "low");
        ChatModel agents = AgentModels.openAi(
                new LlmEndpoint(stub.url(), "test-key", "deepseek-chat"),
                options, Duration.ofSeconds(5), false,
                new VerboseLog(VerboseLogSettings.off()));

        agents.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("q")))
                .build());

        JsonNode body = stub.bodies.getFirst();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("low", body.path("reasoning_effort").asText());
    }

    @Test
    void baseUrl去尾斜杠_拼接chat_completions() throws IOException {
        stub = new StubLlm();
        ChatModel agents = AgentModels.openAi(
                new LlmEndpoint(stub.url() + "/", "k", "deepseek-chat"),
                ChatOptions.decision(100), Duration.ofSeconds(5), false,
                new VerboseLog(VerboseLogSettings.off()));
        stub.enqueue(200, StubLlm.content("ok"));
        var options = new ChatOptions(0.2, 100, false, null);

        agents.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("q")))
                .build());

        assertEquals("/chat/completions", stub.paths.getFirst());
    }

    // ---------- stub ----------

    /** 单线程脚本队列的 OpenAI 兼容 stub；请求体全部记录供断言。 */
    static final class StubLlm implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<int[]> statuses = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();

        StubLlm() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            server.createContext("/chat/completions", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(mapper.readTree(request));
                int status = statuses.poll()[0];
                String body = responses.poll();
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void enqueue(int status, String body) {
            statuses.add(new int[]{status});
            responses.add(body);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        static String content(String text) {
            return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
                    "choices", List.of(Map.of(
                            "message", Map.of("role", "assistant", "content", text),
                            "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
        }
    }
}
