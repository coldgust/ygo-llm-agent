package com.github.coldgust.ygoagent.decision.planexec.llm;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 stub HTTP 服务器钉住传输层的 wire 语义：思考参数、json mode、工具回路轮次。
 * 这些语义全部来自实测（见 pitfalls），换框架后必须逐条保真。
 */
class LangChain4jChatTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private StubLlm stub;
    private LangChain4jChat chat;

    @BeforeEach
    void start() throws IOException {
        stub = new StubLlm();
        chat = new LangChain4jChat(stub.url(), "test-key", "deepseek-chat");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void 基础映射_jsonMode_不带思考参数() {
        stub.enqueue(200, content("{\"answer\":1}"));
        var options = new ChatClient.ChatOptions(0.2, 500, true, Duration.ofSeconds(5), null);

        String out = chat.complete(List.of(SystemMessage.from("sys"), UserMessage.from("usr")), options);

        assertEquals("{\"answer\":1}", out);
        JsonNode body = stub.bodies.getFirst();
        assertEquals("deepseek-chat", body.path("model").asText());
        assertEquals(0.2, body.path("temperature").asDouble());
        assertEquals(500, body.path("max_tokens").asInt());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertTrue(body.path("thinking").isMissingNode());
        assertTrue(body.path("reasoning_effort").isMissingNode());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("usr", body.path("messages").get(1).path("content").asText());
        assertEquals("/chat/completions", stub.paths.getFirst());
    }

    @Test
    void 思考档off_发thinking_disabled与effort_none() {
        stub.enqueue(200, content("x"));

        chat.complete(List.of(UserMessage.from("q")),
                ChatClient.ChatOptions.decision(100, Duration.ofSeconds(5)));

        JsonNode body = stub.bodies.getFirst();
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("none", body.path("reasoning_effort").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void 思考档low_发thinking_enabled与effort() {
        stub.enqueue(200, content("x"));
        var options = new ChatClient.ChatOptions(0.4, 8000, true, Duration.ofSeconds(5), "low");

        chat.complete(List.of(UserMessage.from("q")), options);

        JsonNode body = stub.bodies.getFirst();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("low", body.path("reasoning_effort").asText());
    }

    @Test
    void 工具回路_轮间不带jsonMode_结果按tool_call_id回填() {
        stub.enqueue(200, toolCalls("call-1", "echo_tool", "{\"text\":\"连锁\"}"));
        stub.enqueue(200, content("最终JSON"));
        var options = ChatClient.ChatOptions.decision(500, Duration.ofSeconds(5));

        String out = chat.completeWithTools(
                List.of(SystemMessage.from("s"), UserMessage.from("u")),
                options, List.of(new EchoTool()));

        assertEquals("最终JSON", out);
        assertEquals(2, stub.bodies.size());

        JsonNode first = stub.bodies.get(0);
        assertTrue(first.path("response_format").isMissingNode());
        assertEquals("echo_tool", first.path("tools").get(0).path("function").path("name").asText());
        assertEquals("string", first.path("tools").get(0).path("function").path("parameters")
                .path("properties").path("text").path("type").asText());
        assertEquals("auto", first.path("tool_choice").asText());

        JsonNode second = stub.bodies.get(1);
        JsonNode assistant = second.path("messages").get(2);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("call-1", assistant.path("tool_calls").get(0).path("id").asText());
        JsonNode toolMsg = second.path("messages").get(3);
        assertEquals("tool", toolMsg.path("role").asText());
        assertEquals("call-1", toolMsg.path("tool_call_id").asText());
        assertEquals("回显:连锁", toolMsg.path("content").asText());
    }

    @Test
    void 工具执行异常_错误文案回填成tool结果() {
        stub.enqueue(200, toolCalls("call-1", "fail_tool", "{\"text\":\"x\"}"));
        stub.enqueue(200, content("基于已有信息回答"));

        String out = chat.completeWithTools(List.of(UserMessage.from("u")),
                ChatClient.ChatOptions.decision(500, Duration.ofSeconds(5)),
                List.of(new FailingTool()));

        assertEquals("基于已有信息回答", out);
        JsonNode toolMsg = stub.bodies.get(1).path("messages").get(2);
        assertEquals("tool", toolMsg.path("role").asText());
        assertTrue(toolMsg.path("content").asText().contains("工具检索失败"));
        assertTrue(toolMsg.path("content").asText().contains("LightRAG 不可达"));
    }

    @Test
    void 轮尽收掉工具_强制json_空正文补要一次() {
        for (int i = 0; i < 6; i++) {
            stub.enqueue(200, toolCalls("call-" + i, "echo_tool", "{}"));
        }
        stub.enqueue(200, content(""));
        stub.enqueue(200, content("补要的正文"));

        String out = chat.completeWithTools(List.of(UserMessage.from("u")),
                ChatClient.ChatOptions.decision(500, Duration.ofSeconds(5)),
                List.of(new EchoTool()));

        assertEquals("补要的正文", out);
        assertEquals(8, stub.bodies.size());
        JsonNode forced = stub.bodies.get(6);
        assertEquals("json_object", forced.path("response_format").path("type").asText());
        assertTrue(forced.path("tools").isMissingNode());
        JsonNode reAsk = stub.bodies.get(7);
        JsonNode last = reAsk.path("messages").get(reAsk.path("messages").size() - 1);
        assertEquals("user", last.path("role").asText());
        assertTrue(last.path("content").asText().contains("不要再调用任何工具"));
    }

    @Test
    void 超时不重试() {
        stub.delay(600);
        stub.enqueue(200, content("迟到"));
        var options = new ChatClient.ChatOptions(0.2, 100, true, Duration.ofMillis(200), null);

        assertThrows(ChatClient.ChatException.class,
                () -> chat.complete(List.of(UserMessage.from("q")), options));
        assertEquals(1, stub.bodies.size());
    }

    @Test
    void baseUrl去尾斜杠_拼接chat_completions() throws IOException {
        try (StubLlm trailing = new StubLlm()) {
            var client = new LangChain4jChat(trailing.url() + "/", "k", "deepseek-chat");
            trailing.enqueue(200, content("ok"));
            assertEquals("ok", client.complete(List.of(UserMessage.from("q")),
                    new ChatClient.ChatOptions(0.2, 100, false, Duration.ofSeconds(5), null)));
            assertEquals("/chat/completions", trailing.paths.getFirst());
        }
    }

    // ---------- 测试工具与 stub ----------

    /** @Tool 注解的测试工具：回显参数文本。 */
    static final class EchoTool {
        @Tool(name = "echo_tool", value = "回显文本，测试用")
        public String echo(@P(value = "要回显的文本") String text) {
            return "回显:" + text;
        }
    }

    /** @Tool 注解的测试工具：模拟检索服务不可用。 */
    static final class FailingTool {
        @Tool(name = "fail_tool", value = "总是失败，测试用")
        public String fail(@P(value = "占位参数") String text) {
            throw new RuntimeException("LightRAG 不可达");
        }
    }

    /** 单线程脚本队列的 OpenAI 兼容 stub；请求体全部记录供断言。 */
    static final class StubLlm implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<int[]> statuses = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
        private volatile long delayMillis;

        StubLlm() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(MAPPER.readTree(request));
                int status = statuses.poll()[0];
                String body = responses.poll();
                if (delayMillis > 0) {
                    sleepUninterruptibly(delayMillis);
                }
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

        void delay(long millis) {
            delayMillis = millis;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void sleepUninterruptibly(long millis) {
            long deadline = System.currentTimeMillis() + millis;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                try {
                    Thread.sleep(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static String content(String text) {
        return MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", text),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
    }

    static String toolCalls(String id, String name, String args) {
        return MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", "",
                                "tool_calls", List.of(Map.of(
                                        "id", id,
                                        "type", "function",
                                        "function", Map.of("name", name, "arguments", args)))),
                        "finish_reason", "tool_calls"))));
    }
}
