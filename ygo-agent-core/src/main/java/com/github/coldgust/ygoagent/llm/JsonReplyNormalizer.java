package com.github.coldgust.ygoagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 结构化输出响应的规整层（挂在 OpenAiChatModel 与 ResilientChatModel 之间）。
 *
 * <p>规整方式：经 {@code readTree}（重复键取最后一次 = 模型的收尾值）再序列化，输出无重复键的
 * 单行 JSON。解析失败时原样透传（langchain4j 的报错保留原始上下文，不做二次遮蔽）。
 * 注意：规整后返回的 ChatResponse 只保留 aiMessage（AiServices 只消费它）。
 */
public final class JsonReplyNormalizer implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel delegate;

    public JsonReplyNormalizer(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse response = delegate.chat(request);
        if (response != null && response.aiMessage() != null
                && response.aiMessage().text() != null) {
            String normalized = normalize(response.aiMessage().text());
            if (!normalized.equals(response.aiMessage().text())) {
                return ChatResponse.builder().aiMessage(AiMessage.from(normalized)).build();
            }
        }
        return response;
    }

    /** 重复键取最后一次；非 JSON 或解析失败原样返回。 */
    static String normalize(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            return MAPPER.writeValueAsString(root);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return text;
        }
    }
}
