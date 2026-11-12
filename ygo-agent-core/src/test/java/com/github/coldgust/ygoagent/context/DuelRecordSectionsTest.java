package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决斗记录段的渲染约定：不泄漏效果码、不猜未知身份、「发动」不写成「已生效」、
 * 我方已提交的决策不重复进记录。
 */
class DuelRecordSectionsTest {

    private final CardFacts facts = TestDuels.facts();
    private final DuelRecordSections sections =
            new DuelRecordSections(facts, new CardText(facts));

    /** 静态假端口：条目一次性给全，按 seq/turn 过滤。 */
    private static DuelTimeline timeline(DuelTimeline.Entry... entries) {
        return timelineOf(List.of(entries));
    }

    private static DuelTimeline timelineOf(List<DuelTimeline.Entry> entries) {
        return new DuelTimeline() {
            @Override
            public List<DuelTimeline.Entry> since(long seq) {
                return entries.stream().filter(e -> e.seq() > seq).toList();
            }

            @Override
            public List<DuelTimeline.Entry> turn(int turnNumber) {
                return entries.stream().filter(e -> e.turn() == turnNumber).toList();
            }

            @Override
            public long latestSeq() {
                return entries.stream().mapToLong(DuelTimeline.Entry::seq).max().orElse(0);
            }
        };
    }

    /** 一个回合的最小事件集：回合开始 + 一次效果发动（保证摘要行非空）。 */
    private static List<DuelTimeline.Entry> turnEvents(int turn, DuelTimeline.Side side, long baseSeq) {
        PlayerSide zoneOwner = side == DuelTimeline.Side.SELF ? PlayerSide.SELF : PlayerSide.OPPONENT;
        return List.of(
                new DuelTimeline.Entry.TurnStarted(baseSeq, turn, side),
                new DuelTimeline.Entry.EffectActivated(baseSeq + 1, turn, side,
                        TestDuels.ENGAGE, TestDuels.ENGAGE * 16),
                new DuelTimeline.Entry.CardMoved(baseSeq + 2, turn, side, 0,
                        null, ZoneRef.hand(zoneOwner), null));
    }

    @Test
    void turnTimelineShowsBothSidesFactsAndFiltersOwnDecisions() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, null, ZoneRef.hand(PlayerSide.SELF), null),
                new DuelTimeline.Entry.EffectActivated(3, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1),
                new DuelTimeline.Entry.DecisionMade(4, 3, DuelTimeline.Side.SELF,
                        "MainPhaseRequest", "[Summon card=C1]", "PLAN_STEP"),
                new DuelTimeline.Entry.LpChanged(5, 3, DuelTimeline.Side.SELF, -2300, 5700));

        String section = sections.turnTimeline(3, true, timeline);
        assertTrue(section.contains("[本回合时间线] T3（我方回合）"), section);
        assertTrue(section.contains("我方：闪刀起动-交闪 加入手牌"), section);
        assertTrue(section.contains("对方：发动 王之仆人-黑魔术师"), section);
        assertTrue(section.contains("魔法·陷阱卡全部破坏"), "发动行要能定位到条款:\n" + section);
        assertTrue(section.contains("我方 LP -2300（→5700）"), section);
        assertFalse(section.contains("[Summon"), "我方已提交的决策不重复进记录:\n" + section);
        assertTrue(section.contains("不代表已生效"), "必须声明发动≠生效:\n" + section);
    }

    @Test
    void fullRecordSummarizesOldTurnsAndExpandsRecentOnes() {
        List<DuelTimeline.Entry> entries = new java.util.ArrayList<>();
        entries.addAll(turnEvents(1, DuelTimeline.Side.SELF, 10));
        entries.addAll(turnEvents(2, DuelTimeline.Side.OPPONENT, 20));
        entries.addAll(turnEvents(3, DuelTimeline.Side.SELF, 30));
        entries.addAll(turnEvents(4, DuelTimeline.Side.OPPONENT, 40));
        entries.addAll(turnEvents(5, DuelTimeline.Side.SELF, 50));

        String record = sections.fullRecord(timelineOf(entries));
        assertTrue(record.contains("[决斗记录]"), record);
        assertTrue(record.contains("T1（我方回合）摘要："), "旧回合压成摘要:\n" + record);
        assertTrue(record.contains("T2（对方回合）摘要："), record);
        assertFalse(record.contains("T3（我方回合）摘要"), "最近回合要逐条展开:\n" + record);
        assertTrue(record.contains("T3（我方回合）："), record);
        assertTrue(record.contains("T5（我方回合）："), record);
    }

    @Test
    void unknownCardsAreRenderedAsUnseenNotGuessed() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.CardMoved(2, 2, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.spellTrap(PlayerSide.OPPONENT, 0), "FACE_DOWN_DEFENSE"),
                // 是否发动类请求带的提示文 id 不是效果码：解不出条款时不许冒充定位
                new DuelTimeline.Entry.EffectActivated(3, 2, DuelTimeline.Side.OPPONENT, 0, 97));

        String section = sections.turnTimeline(2, false, timeline);
        assertTrue(section.contains("对方：盖放魔陷到 对方魔陷区#1（内容不可见）"), section);
        assertTrue(section.contains("对方：发动 未知卡"), section);
        assertFalse(section.contains("[效果#"), section);
        assertFalse(section.contains("卡(0)"), section);
    }

    @Test
    void consecutiveIdenticalLinesFoldIntoTimesN() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.CardMoved(1, 3, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.hand(PlayerSide.OPPONENT), null),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.hand(PlayerSide.OPPONENT), null),
                new DuelTimeline.Entry.CardMoved(3, 3, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.hand(PlayerSide.OPPONENT), null));

        String section = sections.turnTimeline(3, false, timeline);
        assertTrue(section.contains("对方：有卡加入手牌（内容不可见） ×3"), section);
    }

    @Test
    void blankTimelineRendersNothing() {
        assertEquals("", sections.turnTimeline(1, true, null));
        assertEquals("", sections.fullRecord(null));
        assertEquals("", sections.turnTimeline(1, true, timeline()));
        assertEquals("", sections.fullRecord(timeline()));
        // 有事件但不属于本回合（也不够成上回合摘要）→ 不渲染空段
        assertEquals("", sections.turnTimeline(3, true, timeline(
                new DuelTimeline.Entry.TurnStarted(1, 1, DuelTimeline.Side.SELF))));
    }
}
