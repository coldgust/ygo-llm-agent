package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CardToolTest {

    private static CardFacts facts() {
        Map<Long, CardFact> map = new LinkedHashMap<>();
        map.put(98266377L, new CardFact(98266377L, "黑寡妇抓锚", "Widow Anchor",
                "SPELL", "速攻魔法", "", "", null, null, null,
                "①：以对方场上1只表侧表示怪兽为对象才能发动……"));
        map.put(61777313L, new CardFact(61777313L, "闪刀姬-零衣", "Sky Striker Ace - Raye",
                "MONSTER", "效果／怪兽", "战士", "光", 1500, 1500, 4,
                "①：把自己场上1只怪兽解放才能发动……"));
        return new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                return Optional.ofNullable(map.get(passcode));
            }

            @Override
            public long knownCount() {
                return map.size();
            }

            @Override
            public List<CardFact> searchByName(String keyword, int limit) {
                return map.values().stream()
                        .filter(f -> f.name().contains(keyword) || f.nameEn().contains(keyword))
                        .limit(limit <= 0 ? 5 : limit)
                        .toList();
            }
        };
    }

    @Test
    void lookupByPasscodeRendersFullFacts() {
        CardTool tool = new CardTool(facts());

        String out = tool.search("61777313", null);

        assertTrue(out.contains("闪刀姬-零衣"), out);
        assertTrue(out.contains("卡号：61777313"), out);
        assertTrue(out.contains("ATK：1500"), out);
        assertTrue(out.contains("效果："), out);
    }

    @Test
    void lookupByNameReturnsSingleCardOrCandidates() {
        CardTool tool = new CardTool(facts());

        String single = tool.search("黑寡妇", null);
        assertTrue(single.contains("黑寡妇抓锚"), single);
        assertTrue(single.contains("Widow Anchor"), single);

        String multi = tool.search("闪刀", null);
        assertTrue(multi.contains("闪刀姬-零衣"), multi);
        assertTrue(multi.contains("61777313"), multi);
    }

    @Test
    void unknownPasscodeIsExplained() {
        CardTool tool = new CardTool(facts());

        String out = tool.search("11111111", null);

        assertTrue(out.contains("没有卡号"), out);
    }

    @Test
    void emptyQueryAsksForInput() {
        CardTool tool = new CardTool(facts());

        String out = tool.search("", null);

        assertTrue(out.contains("请提供卡名或卡号"), out);
    }
}
