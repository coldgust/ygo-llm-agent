package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.support.Texts;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import com.github.coldgust.ygoagent.knowledge.SourceDomain;

/**
 * 规则书检索工具：把"查规则"这件事做成模型可自主调用的函数（OpenAI tools / function calling）。
 */
public final class RulebookTool {

    public static final String NAME = "search_rulebook";

    private static final Logger log = LoggerFactory.getLogger(RulebookTool.class);
    private static final int MAX_RESULT_CHARS = 3_000;

    private final LightRagClient client;
    private final int defaultTopK;

    public RulebookTool(LightRagSettings lightRag, String mode, int defaultTopK) {
        this.client = new LightRagClient(lightRag, mode);
        this.defaultTopK = defaultTopK <= 0 ? 6 : defaultTopK;
    }

    /**
     * 单 workspace 多域共存时的检索侧隔离：规则工具只接受规则域来源。
     */
    static boolean isRuleDomain(String source) {
        // 域名判定集中在一处，`rule:` 与 `rule-`（upload 拒绝冒号）等价
        return SourceDomain.isRule(source);
    }

    /**
     * 执行一次检索；服务不可用时抛异常，由传输层回填错误让模型继续。
     */
    @Tool(name = NAME, value = "查阅游戏王官方规则书（大师规则 v10）原文。适用于：具体裁定、召唤/连锁/战斗的精确条件、"
            + "卡种与区域规则、不熟悉的判定。上下文的[规则速查]已覆盖常识，"
            + "只有需要精确条文或遇到拿不准的判定时才调用。返回相关规则片段与来源。")
    public String search(
            @P(value = "要检索的规则问题或关键词（中英文均可），例如：额外怪兽区 连接怪兽 放置、"
                    + "连锁 咒文速度 反击陷阱、效果被无效 是否破坏")
            String query,
            @P(value = "返回片段数，默认 6，最多 12", required = false)
            Integer topK) {
        if (query == null || query.isBlank()) {
            return "（查询为空：请提供要查阅的规则问题或关键词）";
        }
        int effectiveTopK = topK == null ? defaultTopK : Math.max(1, Math.min(12, topK));
        String text = render(client.query(query, effectiveTopK));
        log.info("规则检索 query='{}' topK={} → {} 字", Texts.flattenClip(query, 40), effectiveTopK, text.length());
        return text;
    }

    private String render(JsonNode root) {
        JsonNode data = root.path("data");
        StringBuilder sb = new StringBuilder();
        int total = 0;

        JsonNode chunks = data.path("chunks");
        if (chunks.isArray()) {
            for (JsonNode chunk : chunks) {
                String content = chunk.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                String source = Texts.firstNonBlank(chunk, "file_path", "id");
                if (!isRuleDomain(source)) {
                    continue; // 单 workspace 多域共存：只吃规则域，避免卡牌/combo/对局片段串味
                }
                sb.append("[规则片段").append(++total).append("]");
                if (!source.isBlank()) {
                    sb.append("（").append(Texts.flattenClip(source, 60)).append("）");
                }
                sb.append("\n").append(Texts.flattenClip(content, 1_200)).append("\n\n");
                if (sb.length() > MAX_RESULT_CHARS) {
                    break;
                }
            }
        }

        if (total == 0) {
            JsonNode entities = data.path("entities");
            if (entities.isArray()) {
                for (JsonNode e : entities) {
                    String desc = e.path("description").asText("");
                    if (desc.isBlank()) {
                        continue;
                    }
                    String name = Texts.firstNonBlank(e, "entity_name", "name");
                    sb.append("- ").append(Texts.flattenClip(name, 40)).append("：")
                            .append(Texts.flattenClip(desc, 300)).append("\n");
                    if (++total >= 8) {
                        break;
                    }
                }
            }
        }

        if (total == 0) {
            return "（规则书未命中相关条文；请基于[规则速查]与卡面信息判断）";
        }
        String out = sb.toString();
        return out.length() <= MAX_RESULT_CHARS ? out : out.substring(0, MAX_RESULT_CHARS) + "…";
    }
}
