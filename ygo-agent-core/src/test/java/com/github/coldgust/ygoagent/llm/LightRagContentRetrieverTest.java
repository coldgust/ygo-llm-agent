package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ContentRetriever 适配器的域过滤与截断：只吃规则域 chunk，超出预算截停。 */
class LightRagContentRetrieverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode root(String... chunks) {
        StringBuilder sb = new StringBuilder("{\"data\":{\"chunks\":[");
        for (int i = 0; i < chunks.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"content\":").append(MAPPER.writeValueAsString(chunks[i]))
                    .append(",\"file_path\":").append(MAPPER.writeValueAsString(sourceOf(i))).append('}');
        }
        return MAPPER.readTree(sb.append("]}}").toString());
    }

    /** 第 i 个 chunk 的来源：偶数规则域、奇数对局域。 */
    private static String sourceOf(int i) {
        return i % 2 == 0 ? "rule-ocg-zh.pdf" : "duel:abc-1";
    }

    @Test
    void 只保留规则域chunk() {
        LightRagContentRetriever retriever = new LightRagContentRetriever(
                new LightRagSettings("http://localhost:9621", "k", 8_000), "mix", 6);
        List<Content> contents = retriever.chunks(root(
                "连锁的咒文速度规则原文", "对手回合的失误教训", "战斗阶段的伤害步骤"));
        assertEquals(2, contents.size(), "duel 域 chunk 要被过滤掉");
        assertTrue(contents.getFirst().textSegment().text().contains("咒文速度"));
        assertEquals("rule-ocg-zh.pdf", contents.getFirst().textSegment().metadata().getString("file_source"));
    }

    @Test
    void 预算与条数上限() {
        LightRagContentRetriever retriever = new LightRagContentRetriever(
                new LightRagSettings("http://localhost:9621", "k", 8_000), "mix", 6);
        String longChunk = "规则".repeat(900); // 1800 字
        List<Content> contents = retriever.chunks(root(longChunk, longChunk, longChunk));
        assertTrue(contents.size() <= 8, "条数上限 8");
        int total = contents.stream().mapToInt(c -> c.textSegment().text().length()).sum();
        assertTrue(total <= 3_000 + 1_800, "预算超限截停（允许最后一条略超）: " + total);
    }

    @Test
    void 空查询与空chunk返回空列表() {
        LightRagContentRetriever retriever = new LightRagContentRetriever(
                new LightRagSettings("http://localhost:9621", "k", 8_000), "mix", 6);
        assertTrue(retriever.chunks(MAPPER.readTree("{\"data\":{}}")).isEmpty());
    }
}
