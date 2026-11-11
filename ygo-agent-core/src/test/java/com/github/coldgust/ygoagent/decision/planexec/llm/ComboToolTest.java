package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComboToolTest {

    private static RecordedComboSource.Entry entry(long id, String archetype, String keyCards) {
        return new RecordedComboSource.Entry(id, archetype, 3, "概述：起手engage检索，耀歌加攻",
                keyCards == null ? List.of() : List.of(keyCards.split(",")),
                List.of("发动魔法《闪刀起动-交闪》", "召唤/特殊召唤《闪刀姬-耀歌》到 怪兽区#2（表侧攻击）"));
    }

    private static RecordedComboSource source(List<RecordedComboSource.Entry> all,
                                              List<RecordedComboSource.Entry> searchResult) {
        return new RecordedComboSource() {
            @Override
            public boolean available() {
                return !all.isEmpty();
            }

            @Override
            public List<String> archetypes() {
                return all.stream().map(RecordedComboSource.Entry::archetype).distinct().sorted().toList();
            }

            @Override
            public List<Entry> recentByArchetype(String archetype, int limit) {
                return all.stream().limit(limit).toList();
            }

            @Override
            public List<Entry> search(String archetype, String keyCard, int limit) {
                return searchResult.stream().limit(limit).toList();
            }
        };
    }

    @Test
    void searchByOpponentArchetypeReturnsEntries() {
        ComboTool tool = new ComboTool(
                source(List.of(entry(1, "SKY_STRIKER", "闪刀起动-交闪,闪刀姬-耀歌")),
                        List.of(entry(1, "SKY_STRIKER", "闪刀起动-交闪,闪刀姬-耀歌"))),
                null);

        String out = tool.search("SKY_STRIKER", null);

        assertTrue(out.contains("录入#1"), out);
        assertTrue(out.contains("关键卡牌：闪刀起动-交闪、闪刀姬-耀歌"), out);
        assertTrue(out.contains("摘要"), out);
    }

    @Test
    void searchByKeyCardOnly() {
        ComboTool tool = new ComboTool(
                source(List.of(), List.of(entry(7, "闪刀", "灰流丽,无限泡影"))),
                null);

        String out = tool.search(null, "灰流丽");

        assertTrue(out.contains("录入#7"), out);
        assertTrue(out.contains("关键卡 7") || out.contains("关键卡 灰流丽"), out);
    }

    @Test
    void blankArgsFallBackToSelfArchetype() {
        ComboTool tool = new ComboTool(
                source(List.of(entry(2, "SKY_STRIKER", "闪刀起动-交闪")),
                        List.of(entry(2, "SKY_STRIKER", "闪刀起动-交闪"))),
                "SKY_STRIKER");

        String out = tool.search(null, null);

        assertTrue(out.contains("录入#2"), out);
    }

    @Test
    void noArgsListsAvailableThemes() {
        ComboTool tool = new ComboTool(
                source(List.of(entry(1, "SKY_STRIKER", null), entry(2, "BLUE_EYES", null)), List.of()),
                null);

        String out = tool.search(null, null);

        assertTrue(out.contains("可用的卡组主题"), out);
        assertTrue(out.contains("SKY_STRIKER"), out);
    }

    @Test
    void noMatchExplainsAndListsThemes() {
        ComboTool tool = new ComboTool(
                source(List.of(entry(1, "SKY_STRIKER", null)), List.of()),
                null);

        String out = tool.search("BLUE_EYES", null);

        assertTrue(out.contains("没有匹配"), out);
        assertTrue(out.contains("SKY_STRIKER"), out);
    }

    @Test
    void searchNeverReturnsMoreThanThree() {
        List<RecordedComboSource.Entry> five = List.of(
                entry(1, "闪刀", null), entry(2, "闪刀", null), entry(3, "闪刀", null),
                entry(4, "闪刀", null), entry(5, "闪刀", null));
        ComboTool tool = new ComboTool(source(five, five), null);

        String out = tool.search("闪刀", null);

        assertFalse(out.contains("录入#4"), out);
        assertTrue(out.contains("共 3 条"), out);
    }

    @Test
    void toolUnavailableWithoutSource() {
        assertFalse(new ComboTool(null, null).available());
        assertEquals(3, ComboTool.SEARCH_LIMIT);
    }
}
