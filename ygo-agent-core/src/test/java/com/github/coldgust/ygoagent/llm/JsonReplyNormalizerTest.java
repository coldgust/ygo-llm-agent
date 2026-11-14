package com.github.coldgust.ygoagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出响应规整层的钉子（实测事故 2026-11-14：deepseek-flash 在 JSON 顶层重复
 * confidence 字段 → record + 重复键 + ignoreUnknown → InvalidDefinitionException，
 * 幕僚每手建议全部解析失败）。
 */
class JsonReplyNormalizerTest {

    /** 固定回复的假模型：记录收到的请求。 */
    private static final class FixedModel implements ChatModel {
        private final String reply;
        int calls;

        FixedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls++;
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    @Test
    void duplicateTopLevelKeysKeepTheLastValue() throws Exception {
        String normalized = JsonReplyNormalizer.normalize(
                "{\"keep\": false, \"confidence\": 0.1, \"strategy\": \"s\", \"confidence\": 0.7}");
        JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(normalized);
        assertEquals(0.7, root.get("confidence").asDouble(), "重复键取最后一次（模型收尾值）");
    }

    @Test
    void cleanJsonIsReSerializedWithoutChangeInMeaning() {
        assertEquals("{\"keep\":true,\"confidence\":0.5}",
                JsonReplyNormalizer.normalize("{\"keep\": true, \"confidence\": 0.5}"));
    }

    @Test
    void nonJsonPassesThroughUnchanged() {
        String junk = "我觉得应该打对面";
        assertEquals(junk, JsonReplyNormalizer.normalize(junk),
                "解析失败原样透传，langchain4j 的报错保留原始上下文");
    }

    @Test
    void chatRebuildsResponseWhenTextChangesAndPassesThroughOtherwise() throws Exception {
        FixedModel inner = new FixedModel("{\"confidence\": 0.1, \"confidence\": 0.9}");
        JsonReplyNormalizer normalizer = new JsonReplyNormalizer(inner);

        ChatRequest request = ChatRequest.builder().messages(
                dev.langchain4j.data.message.UserMessage.from("p")).build();
        ChatResponse out = normalizer.chat(request);
        assertEquals(0.9, out.aiMessage().text() == null ? 0
                        : new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        out.aiMessage().text()).get("confidence").asDouble(),
                "规整后只剩最后一次 confidence");
        assertEquals(1, inner.calls);

        // 文本无需规整时原响应对象透传（不重建）
        FixedModel clean = new FixedModel("{\"keep\":true}");
        ChatResponse passthrough = new JsonReplyNormalizer(clean)
                .chat(ChatRequest.builder().messages(
                        dev.langchain4j.data.message.UserMessage.from("p")).build());
        assertEquals("{\"keep\":true}", passthrough.aiMessage().text());
    }
}
