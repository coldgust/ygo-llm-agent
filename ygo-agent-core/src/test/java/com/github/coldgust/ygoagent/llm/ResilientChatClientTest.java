package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 重试策略的特征测试：超时不重试、空正文放宽重试、400 丢思考参数、工具回路降级。 */
class ResilientChatClientTest {

    @Test
    void 空正文_放宽到下限2000重试一次() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add("");
        fake.completeResults.add("ok");
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 100, true, Duration.ofSeconds(5), null);

        assertEquals("ok", client.complete(List.of(UserMessage.from("q")), options));
        assertEquals(2000, fake.seenOptions.getLast().maxTokens());
        assertEquals(2, fake.seenOptions.size());
    }

    @Test
    void 空正文_放宽上限8000() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add("");
        fake.completeResults.add("ok");
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 6000, true, Duration.ofSeconds(5), null);

        assertEquals("ok", client.complete(List.of(UserMessage.from("q")), options));
        assertEquals(8000, fake.seenOptions.getLast().maxTokens());
    }

    @Test
    void 超时不重试() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add(new ChatClient.ChatException("LLM 调用超时: 10s",
                new HttpTimeoutException("request timeout")));
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 100, true, Duration.ofSeconds(5), "low");

        assertThrows(ChatClient.ChatException.class,
                () -> client.complete(List.of(UserMessage.from("q")), options));
        assertEquals(1, fake.seenOptions.size());
    }

    @Test
    void 消息含timed_out也算超时_不重试() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add(new ChatClient.ChatException("Something timed out", null));
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));

        assertThrows(ChatClient.ChatException.class,
                () -> client.complete(List.of(UserMessage.from("q")),
                        new ChatClient.ChatOptions(0.2, 100, false, Duration.ofSeconds(5), null)));
        assertEquals(1, fake.seenOptions.size());
    }

    @Test
    void http400带思考参数_丢参数重试一次() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add(new ChatClient.ChatException("LLM HTTP 400: unknown field", null));
        fake.completeResults.add("ok");
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 4000, true, Duration.ofSeconds(5), "low");

        assertEquals("ok", client.complete(List.of(UserMessage.from("q")), options));
        assertEquals(2, fake.seenOptions.size());
        assertNull(fake.seenOptions.getLast().reasoning());
        assertEquals(4000, fake.seenOptions.getLast().maxTokens());
    }

    @Test
    void 其他错误_原样重试一次() {
        FakeChat fake = new FakeChat();
        fake.completeResults.add(new ChatClient.ChatException("LLM HTTP 503: busy", null));
        fake.completeResults.add("ok");
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 100, false, Duration.ofSeconds(5), null);

        assertEquals("ok", client.complete(List.of(UserMessage.from("q")), options));
        assertEquals(2, fake.seenOptions.size());
        assertEquals(options, fake.seenOptions.getLast());
    }

    @Test
    void 工具回路失败_降级为普通补全() {
        FakeChat fake = new FakeChat();
        fake.completeWithToolsFailure = new ChatClient.ChatException("LLM HTTP 500: boom", null);
        fake.completeResults.add("兜底回答");
        var client = new ResilientChatClient(fake, new VerboseLog(VerboseLogSettings.off()));
        var options = new ChatClient.ChatOptions(0.2, 100, true, Duration.ofSeconds(5), null);
        List<Object> tools = List.of(new Object());

        assertEquals("兜底回答", client.completeWithTools(List.of(UserMessage.from("q")), options, tools));
        assertEquals(1, fake.completeWithToolsCalls);
        assertEquals(1, fake.seenOptions.size());
    }

    /** 记录收到的 options 与脚本化结果的假实现。 */
    static final class FakeChat implements ChatClient {

        final Queue<Object> completeResults = new ArrayDeque<>();
        final List<ChatOptions> seenOptions = new ArrayList<>();
        RuntimeException completeWithToolsFailure;
        int completeWithToolsCalls;

        @Override
        public String complete(List<ChatMessage> messages, ChatOptions options) {
            seenOptions.add(options);
            Object next = completeResults.poll();
            if (next instanceof ChatClient.ChatException e) {
                throw e;
            }
            return (String) next;
        }

        @Override
        public String completeWithTools(List<ChatMessage> messages, ChatOptions options,
                                        List<Object> tools) {
            completeWithToolsCalls++;
            if (completeWithToolsFailure != null) {
                throw completeWithToolsFailure;
            }
            return complete(messages, options);
        }
    }
}
