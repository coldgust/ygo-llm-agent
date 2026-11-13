package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.knowledge.SourceDomain;
import com.github.coldgust.ygoagent.support.Texts;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * LightRAG 的 {@link ContentRetriever} 适配器：把"规则书语义检索"接到框架的 RAG 插件点上。
 * 只保留规则域 chunk（排除法，兼容历史无前缀 chunk——见 pitfalls）；底层只取证据不二次生成。
 * <p>当前接入无延迟压力的叙事链路（combo 录入摘要）；决策热路径维持 agentic 工具检索
 * （模型自己决定何时翻规则书，不让系统每步预检索）。
 */
public final class LightRagContentRetriever implements ContentRetriever {

    private static final int MAX_RESULT_CHARS = 3_000;

    private final LightRagClient client;
    private final int topK;

    public LightRagContentRetriever(LightRagSettings lightRag, String mode, int topK) {
        this.client = new LightRagClient(lightRag, mode);
        this.topK = topK <= 0 ? 6 : topK;
    }

    /** 检索失败返回空（叙事链路：知识库挂了只是少一个参考，不拖垮任务）。 */
    @Override
    public List<Content> retrieve(Query query) {
        String q = query.text();
        if (q == null || q.isBlank()) {
            return List.of();
        }
        try {
            return chunks(client.query(q, topK));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 规则域 chunk（与 RulebookTool 同一渲染取舍，返回给框架做内容注入）。 */
    List<Content> chunks(JsonNode root) {
        JsonNode data = root.path("data");
        JsonNode chunks = data.path("chunks");
        if (!chunks.isArray()) {
            return List.of();
        }
        List<Content> out = new ArrayList<>();
        int total = 0;
        for (JsonNode chunk : chunks) {
            String content = chunk.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            String source = Texts.firstNonBlank(chunk, "file_path", "id");
            if (!SourceDomain.isRule(source)) {
                continue; // 单 workspace 多域共存：只吃规则域
            }
            String text = Texts.flattenClip(content, 1_200);
            Metadata metadata = new Metadata();
            if (!source.isBlank()) {
                metadata.put("file_source", Texts.flattenClip(source, 60));
            }
            out.add(Content.from(TextSegment.from(text, metadata)));
            total += text.length();
            if (total > MAX_RESULT_CHARS || out.size() >= 8) {
                break;
            }
        }
        return out;
    }
}
