package com.github.coldgust.ygoagent.decision.planexec.llm;

import com.github.coldgust.ygoagent.decision.planexec.CardFact;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查卡工具：让模型按卡名或卡号拿到一张卡的完整事实。
 *
 * <p>为什么需要它：上下文只给"当前可见卡"的摘要，模型想确认某张卡的完整效果、
 * 攻守/等级/种族时，凭记忆答就是幻觉。工具直接把卡库里的权威字段读出来，
 * 比让模型"回忆"可靠得多。
 */
public final class CardTool {

    public static final String NAME = "find_card";

    private static final Logger log = LoggerFactory.getLogger(CardTool.class);

    private final CardFacts facts;

    public CardTool(CardFacts facts) {
        this.facts = facts;
    }

    public boolean available() {
        return facts != null && facts.knownCount() > 0;
    }

    public List<ChatClient.Tool> tools() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "卡名（中文/英文均可，支持部分匹配）或卡号（passcode），"
                + "如 \"零衣\"、\"Rayé\"、\"61777313\"");

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "按卡名匹配时最多返回几条候选，默认 5，最多 10");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        properties.put("limit", limit);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of("query"));

        return List.of(new ChatClient.Tool(NAME,
                "查一张游戏王卡的完整信息（卡号、中英文名、种类、种族/属性、攻守、等级、效果全文）。"
                        + "当你不确定某张卡的效果/数值，或上下文只给了卡名需要确认细节时调用；"
                        + "不要凭记忆回答卡面事实。",
                params));
    }

    public ChatClient.ToolHandler handler() {
        return call -> search(call.arguments());
    }

    String search(String argumentsJson) {
        String query = "";
        int limit = 5;
        try {
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                JsonNode args = JsonMapper.builder().build().readTree(argumentsJson);
                query = args.path("query").asText("").trim();
                if (args.path("limit").isNumber()) {
                    limit = Math.max(1, Math.min(10, args.path("limit").asInt(5)));
                }
            }
        } catch (RuntimeException e) {
            // 参数不是合法 JSON：按空查询处理，提示模型怎么传
        }

        if (query.isBlank()) {
            return "（请提供卡名或卡号，例如 query=\"零衣\" 或 query=\"61777313\"）";
        }
        if (query.matches("\\d{1,10}")) {
            long passcode = Long.parseLong(query);
            return facts.byPasscode(passcode)
                    .map(this::render)
                    .orElseGet(() -> "（卡库里没有卡号 " + passcode + "）");
        }

        List<CardFact> matches = facts.searchByName(query, limit);
        if (matches.isEmpty()) {
            return "（卡库里没有名字包含 \"" + query + "\" 的卡；换个关键词或直接给卡号）";
        }
        if (matches.size() == 1) {
            return render(matches.get(0));
        }
        log.info("查卡：query='{}' → {} 条候选", query, matches.size());
        StringBuilder sb = new StringBuilder("名字包含 \"").append(query).append("\" 的卡（")
                .append(matches.size()).append(" 张，可用卡号精确查）：\n");
        for (CardFact fact : matches) {
            sb.append("- ").append(fact.name()).append("（").append(fact.passcode()).append("）");
            if (fact.nameEn() != null && !fact.nameEn().isBlank()) {
                sb.append(" / ").append(fact.nameEn());
            }
            sb.append("｜").append(nullSafe(fact.cardType())).append("\n");
        }
        return sb.toString();
    }

    private String render(CardFact fact) {
        StringBuilder sb = new StringBuilder();
        sb.append("卡牌：").append(fact.name());
        if (fact.nameEn() != null && !fact.nameEn().isBlank()) {
            sb.append("（").append(fact.nameEn()).append("）");
        }
        sb.append("\n卡号：").append(fact.passcode());
        sb.append("\n类型：").append(nullSafe(fact.cardType()));
        if (fact.race() != null && !fact.race().isBlank()) {
            sb.append("｜种族：").append(fact.race());
        }
        if (fact.attribute() != null && !fact.attribute().isBlank()) {
            sb.append("｜属性：").append(fact.attribute());
        }
        if (fact.level() != null) {
            sb.append("\n等级/阶级：").append(fact.level());
        }
        if (fact.atk() != null || fact.defense() != null) {
            sb.append("\nATK：").append(fact.atk() == null ? "?" : fact.atk())
                    .append(" / DEF：").append(fact.defense() == null ? "?" : fact.defense());
        }
        if (fact.effect() != null && !fact.effect().isBlank()) {
            sb.append("\n效果：").append(fact.effect());
        }
        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
