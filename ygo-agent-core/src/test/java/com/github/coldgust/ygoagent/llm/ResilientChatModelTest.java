package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重试策略的特征测试：超时不重试、空正文放宽重试、400 丢思考参数、其他异常补试一次。 */
class ResilientChatModelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static ChatRequest request(ChatOptions options) {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("q")))
                .parameters(TierParameters.of(options, "deepseek-chat", false))
                .build();
    }

    @Test
    void 空正文_放宽到下限2000重试一次() {
        FakeModel fake = new FakeModel("", "ok");
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 100, true, TIMEOUT, null);

        assertEquals("ok", model.chat(request(options)).aiMessage().text());
        assertEquals(2000, maxTokens(fake.requests.getLast()));
        assertEquals(2, fake.requests.size());
    }

    @Test
    void 空正文_放宽上限8000() {
        FakeModel fake = new FakeModel("", "ok");
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 6000, true, TIMEOUT, null);

        assertEquals("ok", model.chat(request(options)).aiMessage().text());
        assertEquals(8000, maxTokens(fake.requests.getLast()));
    }

    @Test
    void 工具调用响应不算空正文() {
        FakeModel fake = new FakeModel();
        fake.enqueueToolCall("call-1", "t", "{}");
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 100, true, TIMEOUT, null);

        assertTrue(model.chat(request(options)).aiMessage().hasToolExecutionRequests());
        assertEquals(1, fake.requests.size());
    }

    @Test
    void 超时不重试() {
        FakeModel fake = new FakeModel();
        fake.failWith(new LlmChatException("LLM 调用超时: 10s",
                new HttpTimeoutException("request timeout")));
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 100, true, TIMEOUT, "low");

        assertThrows(LlmChatException.class, () -> model.chat(request(options)));
        assertEquals(1, fake.requests.size());
    }

    @Test
    void 消息含timed_out也算超时_不重试() {
        FakeModel fake = new FakeModel();
        fake.failWith(new LlmChatException("Something timed out", null));
        var model = new ResilientChatModel(fake);

        assertThrows(LlmChatException.class,
                () -> model.chat(request(new ChatOptions(0.2, 100, false, TIMEOUT, null))));
        assertEquals(1, fake.requests.size());
    }

    @Test
    void http400带思考参数_丢参数重试一次() {
        FakeModel fake = new FakeModel("ok");
        fake.failWith(new LlmChatException("LLM HTTP 400: unknown field", null));
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 4000, true, TIMEOUT, "low");

        assertEquals("ok", model.chat(request(options)).aiMessage().text());
        assertEquals(2, fake.requests.size());
        assertNull(reasoningEffort(fake.requests.getLast()));
        assertEquals(4000, maxTokens(fake.requests.getLast()));
    }

    @Test
    void 其他错误_原样重试一次() {
        FakeModel fake = new FakeModel("ok");
        fake.failWith(new LlmChatException("LLM HTTP 503: busy", null));
        var model = new ResilientChatModel(fake);
        var options = new ChatOptions(0.2, 100, false, TIMEOUT, null);

        assertEquals("ok", model.chat(request(options)).aiMessage().text());
        assertEquals(2, fake.requests.size());
        assertEquals(100, maxTokens(fake.requests.getLast()));
    }

    @Test
    void 原生HttpException_转换为带状态码的LlmChatException() {
        FakeModel fake = new FakeModel();
        fake.failWith(new HttpException(429, "rate limited"));
        fake.failWith(new HttpException(429, "rate limited again")); // 重试一次也失败
        var model = new ResilientChatModel(fake);

        LlmChatException e = assertThrows(LlmChatException.class,
                () -> model.chat(request(new ChatOptions(0.2, 100, false, TIMEOUT, null))));
        assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
        assertEquals(2, fake.requests.size());
    }

    private static int maxTokens(ChatRequest request) {
        return ((OpenAiChatRequestParameters) request.parameters()).maxOutputTokens();
    }

    private static String reasoningEffort(ChatRequest request) {
        return ((OpenAiChatRequestParameters) request.parameters()).reasoningEffort();
    }

    /** 脚本化结果/异常队列的假模型。 */
    static final class FakeModel implements ChatModel {

        final List<ChatRequest> requests = new ArrayList<>();
        private final Deque<Object> results = new ArrayDeque<>();

        FakeModel(String... texts) {
            for (String t : texts) {
                results.add(AiMessage.from(t));
            }
        }

        void enqueueToolCall(String id, String name, String args) {
            results.add(new AiMessage(List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id(id).name(name).arguments(args).build())));
        }

        void failWith(RuntimeException e) {
            if (results.isEmpty()) {
                results.add(e);
            } else {
                // 先失败一次，之后走预置回复队列
                ArrayDeque<Object> next = new ArrayDeque<>();
                next.add(e);
                next.addAll(results);
                results.clear();
                results.addAll(next);
            }
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            Object next = results.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof AiMessage ai) {
                return ChatResponse.builder().aiMessage(ai).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("（无脚本回复）")).build();
        }
    }
}
