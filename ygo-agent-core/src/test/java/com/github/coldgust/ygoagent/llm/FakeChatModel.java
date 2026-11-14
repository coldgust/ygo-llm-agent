package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可编程假模型：按调用序返回预置文本回复（超出后重复最后一条），记录收到的完整请求。
 * AiServices 代理直接架在它上面——解析（POJO）、模板渲染、工具回路都走真实框架路径。
 */
public class FakeChatModel implements ChatModel {

    private final List<String> replies;
    private final AtomicInteger index = new AtomicInteger();
    /** 规划器 worker 与 ④ 竞速线程并发追加，断言线程同时流式读——必须并发安全（CI 出过 CME）。 */
    public final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    public boolean fail = false;

    public FakeChatModel(String... replies) {
        this.replies = List.of(replies.length == 0 ? new String[]{"{\"keep\": true}"} : replies);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        if (fail) {
            throw new LlmChatException("boom");
        }
        int i = index.getAndIncrement();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(replies.get(Math.min(i, replies.size() - 1))))
                .build();
    }

    /** 最近一次请求的全部消息。 */
    public List<ChatMessage> lastMessages() {
        return requests.getLast().messages();
    }

    /**
     * 给假模型套上默认档位参数（模拟生产里 bake 进模型的 defaultRequestParameters），
     * 供需要断言 wire 参数的测试走真实的 ResilientChatModel 合并路径。
     * 返回独立包装（同一个假模型可按不同档位套多个包装，回复序列共享）。
     */
    public ChatModel baked(ChatOptions tier, boolean withTools) {
        OpenAiChatRequestParameters defaults = TierParameters.of(tier, "deepseek-chat", withTools);
        FakeChatModel parent = this;
        return new ChatModel() {
            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return defaults;
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return parent.chat(request);
            }
        };
    }

    /** 给任意模型套上默认档位参数（Recorder 等自建假模型用）。 */
    public static ChatModel baked(ChatModel delegate, ChatOptions tier, boolean withTools) {
        OpenAiChatRequestParameters defaults = TierParameters.of(tier, "deepseek-chat", withTools);
        return new ChatModel() {
            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return defaults;
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return delegate.chat(request);
            }
        };
    }

    /** 最近一次请求里最后一条 user 消息的文本。 */
    public String lastUserText() {
        List<ChatMessage> messages = lastMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u) {
                return u.singleText();
            }
        }
        return "";
    }
}
