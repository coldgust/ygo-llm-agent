package com.github.coldgust.ygoagent.decision.planexec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * data/card_rag/cards.jsonl 的本地查表实现（v6 §3.3 线 2）。
 * <p>启动时一次性加载（15019 条，内存 ~20MB）；只取决策所需的确定性字段。
 * <p>cards.jsonl 字段：id/name/name_en/card_type/race/attribute/atk/def/level/monster_effect/pendulum_effect。
 */
public final class CardRagFactService implements CardFacts {

    private static final Logger log = LoggerFactory.getLogger(CardRagFactService.class);

    private final Map<Long, CardFact> byPasscode;

    private CardRagFactService(Map<Long, CardFact> byPasscode) {
        this.byPasscode = Map.copyOf(byPasscode);
    }

    public static CardRagFactService load(Path jsonlPath) throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Map<Long, CardFact> map = new HashMap<>();
        int lineNo = 0;
        try (var reader = Files.newBufferedReader(jsonlPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    CardFact fact = parse(node);
                    if (fact.passcode() > 0) {
                        map.putIfAbsent(fact.passcode(), fact);
                    }
                } catch (RuntimeException e) {
                    log.warn("card_rag 第 {} 行解析失败，跳过: {}", lineNo, e.toString());
                }
            }
        }
        log.info("CardRagFactService 加载 {} 张卡（{} 行）", map.size(), lineNo);
        return new CardRagFactService(map);
    }

    static CardFact parse(JsonNode node) {
        long id = node.path("id").asLong(0);
        String cardType = node.path("card_type").asText("");
        Integer atk = intOrNull(node.path("atk"));
        Integer def = intOrNull(node.path("def"));
        Integer level = intOrNull(node.path("level"));
        String effect = textOrNull(node.path("monster_effect"));
        String pendulum = textOrNull(node.path("pendulum_effect"));
        if (pendulum != null && effect != null) {
            effect = "【灵摆】" + pendulum + "\n【怪兽】" + effect;
        }
        return new CardFact(
                id,
                node.path("name").asText(""),
                node.path("name_en").asText(""),
                kindOf(cardType),
                cardType,
                joinList(node.path("race")),
                joinList(node.path("attribute")),
                atk,
                def,
                level,
                effect);
    }

    private static String kindOf(String cardType) {
        if (cardType == null) {
            return "UNKNOWN";
        }
        if (cardType.contains("怪兽")) {
            return "MONSTER";
        }
        if (cardType.contains("陷阱")) {
            return "TRAP";
        }
        if (cardType.contains("魔法")) {
            return "SPELL";
        }
        return "UNKNOWN";
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    private static String joinList(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        array.forEach(n -> {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(n.asText());
        });
        return sb.toString();
    }

    @Override
    public Optional<CardFact> byPasscode(long passcode) {
        return Optional.ofNullable(byPasscode.get(passcode));
    }

    @Override
    public List<CardFact> searchByName(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String query = keyword.toLowerCase(Locale.ROOT).trim();
        int max = limit <= 0 ? 5 : Math.min(limit, 20);
        return byPasscode.values().stream()
                .filter(fact -> fact.name().toLowerCase(Locale.ROOT).contains(query)
                        || fact.nameEn().toLowerCase(Locale.ROOT).contains(query))
                .limit(max)
                .toList();
    }

    @Override
    public long knownCount() {
        return byPasscode.size();
    }
}
