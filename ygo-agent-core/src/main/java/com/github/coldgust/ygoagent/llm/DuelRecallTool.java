package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.support.Texts;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import com.github.coldgust.ygoagent.knowledge.SourceDomain;

/**
 * 历史对局检索工具：从 LightRAG 里的"对局复盘"召回与当前局面相似的历史经验。
 */
public final class DuelRecallTool {

    public static final String NAME = "search_duels";

    private static final Logger log = LoggerFactory.getLogger(DuelRecallTool.class);
    private static final int MAX_RESULT_CHARS = 4_000;
    /** 单条复盘的字数上限：一份完整复盘约 1500 字，留出余量给同一来源的多个 chunk。 */
    private static final int MAX_CHUNK_CHARS = 1_600;
    /** 超预算时优先保留的小节（顺序即优先级）：先给结论，再给能指导当下决策的教训。 */
    private static final List<String> KEEP_SECTIONS = List.of(
            "一句话结论", "转折点", "失误与代价", "下次遇到类似局面");

    private final LightRagClient client;
    private final int defaultLimit;

    public DuelRecallTool(LightRagSettings lightRag, String mode, int defaultLimit) {
        this.client = new LightRagClient(lightRag, mode);
        this.defaultLimit = defaultLimit <= 0 ? 4 : Math.min(defaultLimit, 10);
    }

    @Tool(name = NAME, value = "检索历史对局复盘（我方主题、先后手、胜负，以及关键决策/转折点/失误与教训）。"
            + "当当前局面让你觉得'以前遇到过'、或想参考历史经验/教训时调用；"
            + "返回最相似的对局复盘，作为参考而非命令。")
    public String search(
            @P(value = "当前局面或你想参考的问题，例如："
                    + "我方闪刀后手，对方清后场后OTK；或 我之前怎么处理增殖的G")
            String query,
            @P(value = "返回对局摘要条数，默认 4，最多 10", required = false)
            Integer limit) {
        if (query == null || query.isBlank()) {
            return "（请描述当前局面或想参考的问题，例如 query=\"后手闪刀被清后场\"）";
        }
        int effectiveLimit = limit == null ? defaultLimit : Math.max(1, Math.min(10, limit));
        String text = render(client.query(query, Math.max(effectiveLimit * 2, 8)), effectiveLimit);
        log.info("对局摘要检索 query='{}' → {} 字", Texts.flattenClip(query, 40), text.length());
        return text;
    }

    /**
     * 只保留 duel 域的片段。
     */
    String render(JsonNode root, int limit) {
        JsonNode chunks = root.path("data").path("chunks");
        if (!chunks.isArray()) {
            return "（没有匹配的历史对局复盘）";
        }
        Map<String, StringBuilder> bySource = new LinkedHashMap<>();
        for (JsonNode chunk : chunks) {
            String source = Texts.firstNonBlank(chunk, "file_path", "id");
            if (!isDuelDomain(source)) {
                continue;
            }
            String content = chunk.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            StringBuilder merged = bySource.get(source);
            if (merged == null) {
                if (bySource.size() >= limit) {
                    continue;
                }
                merged = new StringBuilder();
                bySource.put(source, merged);
            }
            appendWithoutOverlap(merged, content);
        }
        if (bySource.isEmpty()) {
            return "（没有匹配的历史对局复盘；按当前局面自行判断）";
        }

        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (var entry : bySource.entrySet()) {
            String merged = dedupeRepeatedLines(entry.getValue().toString());
            sb.append("[历史对局 ").append(++total).append("]（").append(Texts.flattenClip(entry.getKey(), 60)).append("）\n")
                    .append(compact(merged, MAX_CHUNK_CHARS)).append("\n\n");
            if (sb.length() > MAX_RESULT_CHARS) {
                break;
            }
        }
        String out = sb.toString();
        return out.length() <= MAX_RESULT_CHARS ? out : out.substring(0, MAX_RESULT_CHARS) + "…";
    }

    /**
     * 按小节压缩一份复盘。
     */
    static String compact(String content, int limit) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.length() <= limit) {
            return text;
        }
        int firstSection = text.indexOf("## ");
        if (firstSection < 0) {
            return Texts.flattenClip(text, limit); // 老格式（模板摘要/纯文本）：只能截断
        }
        String head = text.substring(0, firstSection).trim();
        Map<String, String> sections = splitSections(text.substring(firstSection));

        StringBuilder sb = new StringBuilder(head.isEmpty() ? "" : head + "\n\n");
        for (String keep : KEEP_SECTIONS) {
            String body = sections.get(keep);
            if (body == null || body.isBlank()) {
                continue;
            }
            String block = "## " + keep + "\n" + body.trim() + "\n\n";
            if (sb.length() + block.length() > limit) {
                continue;
            }
            sb.append(block);
        }
        String out = sb.toString().trim();
        // 一个小节都放不下时，退回普通截断，至少别返回空
        return out.isEmpty() ? Texts.flattenClip(text, limit) : out;
    }

    /**
     * 逐行去掉重复内容。
     */
    private static String dedupeRepeatedLines(String text) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String key = line.strip();
            if ("##".equals(key)) {
                continue; // 切块边界把 "## 标题" 的标题部分切走了，只剩一个空标题
            }
            if (key.length() >= 20 && !seen.add(key)) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * 追加一个 chunk，并去掉与已有文本末尾的重叠。
     */
    private static void appendWithoutOverlap(StringBuilder merged, String content) {
        String text = content.trim();
        if (text.isEmpty()) {
            return;
        }
        // 比较"已有文本的尾部"时要忽略尾部空白：拼接时补的换行会让 endsWith 永远差一个字符
        int end = merged.length();
        while (end > 0 && Character.isWhitespace(merged.charAt(end - 1))) {
            end--;
        }
        int maxOverlap = Math.min(Math.min(end, text.length()), 400);
        for (int k = maxOverlap; k > 0; k--) {
            if (merged.substring(end - k, end).equals(text.substring(0, k))) {
                text = text.substring(k).stripLeading();
                break;
            }
        }
        if (text.isEmpty()) {
            return;
        }
        if (!merged.isEmpty() && merged.charAt(merged.length() - 1) != '\n') {
            merged.append('\n');
        }
        merged.append(text).append('\n');
    }

    /** 把 `## 标题` 切成 标题 → 正文；标题不含 `## `。 */    private static Map<String, String> splitSections(String markdown) {
        Map<String, String> sections = new LinkedHashMap<>();
        String[] lines = markdown.split("\n", -1);
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (current != null) {
                    sections.put(current, body.toString());
                }
                current = line.substring(3).trim();
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) {
            sections.put(current, body.toString());
        }
        return sections;
    }

    /** 对局域来源（`duel:` 或 `duel-`，与服务端写入保持一致）。 */
    static boolean isDuelDomain(String source) {
        return SourceDomain.isDuel(source);
    }
}
