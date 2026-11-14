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
 * 决斗记录流的逐事件渲染约定（2026-11-14 用户指令：不用摘要聚合，直推逐事件原文）：
 * 不泄漏效果码、不猜未知身份、「发动」不写成「已生效」、回合边界有边界行、
 * 我方已提交的决策不重复进记录（由 feed 应答行承载）。
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

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void linesShowBothSidesFactsAndFilterOwnDecisions() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, null, ZoneRef.hand(PlayerSide.SELF), null),
                new DuelTimeline.Entry.EffectActivated(3, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1),
                new DuelTimeline.Entry.DecisionMade(4, 3, DuelTimeline.Side.SELF,
                        "MainPhaseRequest", "[Summon card=C1]", "PLAN_STEP"),
                new DuelTimeline.Entry.LpChanged(5, 3, DuelTimeline.Side.SELF, -2300, 5700));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("我方：闪刀起动-交闪 加入手牌"), section);
        assertTrue(section.contains("对方：发动 王之仆人-黑魔术师"), section);
        assertTrue(section.contains("魔法·陷阱卡全部破坏"), "发动行要能定位到条款:\n" + section);
        assertTrue(section.contains("我方 LP -2300（→5700）"), section);
        assertFalse(section.contains("[Summon"), "我方已提交的决策不重复进记录（应答行由 feed 渲染）:\n" + section);
    }

    @Test
    void multiClauseActivationShowsLocationAndAllClausesForTheModelToJudge() {
        // 用户指令：多条款卡解不出条款时（MDPro3 分叉的 CHAINING desc 是类别值，不带效果序号），
        // 给 来源区域 + 效果全文，让 LLM 自己判断发动了哪条——创造的 ① 场上·手卡发动、
        // ② 墓地发动，triggering 区域就能分开；绝不猜一条冒充定位
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.EffectActivated(2, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, 0, 1, ZoneRef.graveyard(PlayerSide.OPPONENT)));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("对方：发动 王之仆人-黑魔术师（对方墓地）："),
                "来源区域要给出（墓地 → ②）:\n" + section);
        assertTrue(section.contains("①：这张卡的卡名只要在场上·墓地存在当作「黑魔术师」使用"),
                "全部条款全文要给出（①）:\n" + section);
        assertTrue(section.contains("②：这张卡在手卡存在"), "全部条款全文要给出（②）:\n" + section);
        assertTrue(section.contains("／"), "条款之间用 ／ 分隔:\n" + section);
    }

    @Test
    void multiClauseActivationWithoutAKnownZoneStillShowsAllClauses() {
        // 区域拿不到（旧日志/协议缺字段）时仍然给全文，只是没有位置线索
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.EffectActivated(2, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, 0, 1));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("对方：发动 王之仆人-黑魔术师：①："), section);
        assertFalse(section.contains("（对方"), "没有区域就不要编一个:\n" + section);
    }

    @Test
    void singleClauseActivationStillNamesItsOnlyClause() {
        // 单条款卡（交闪只有 ①）在描述码解不出时仍按唯一那条给全文——这是协议经常送 0 时的兜底
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.EffectActivated(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, 0, 1));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("我方：发动 闪刀起动-交闪"), section);
        assertTrue(section.contains("①：从卡组把「闪刀起动物」加入手卡"),
                "单条款卡要给全文（描述码 0 解不出，按唯一那条兜底）:\n" + section);
    }

    @Test
    void turnStartedRendersABoundaryLine() {
        // 直推全文后，回合分界由边界行承载（不再有摘要行的"T3（对方回合）"头）
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, null, ZoneRef.hand(PlayerSide.SELF), null));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("〔第 3 回合 · 我方回合〕"), "回合边界行:\n" + section);
        assertTrue(section.indexOf("〔第 3 回合") < section.indexOf("加入手牌"),
                "边界行在最前（该回合的第一条事件）:\n" + section);
    }

    @Test
    void unknownCardsAreRenderedAsUnseenNotGuessed() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.CardMoved(2, 2, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.spellTrap(PlayerSide.OPPONENT, 0), "FACE_DOWN_DEFENSE"),
                // 是否发动类请求带的提示文 id 不是效果码：解不出条款时不许冒充定位
                new DuelTimeline.Entry.EffectActivated(3, 2, DuelTimeline.Side.OPPONENT, 0, 97));

        String section = joined(sections.lines(timeline.turn(2)));
        assertTrue(section.contains("对方：盖放魔陷到 对方魔陷区1（内容不可见）"), section);
        assertTrue(section.contains("对方：发动 未知卡"), section);
        assertFalse(section.contains("[效果#"), section);
        assertFalse(section.contains("卡(0)"), section);
    }

    @Test
    void backrowFlipUpIsNotAPositionChange() {
        // 实机事实错误：记录里出现"我方：禁忌的一滴 改变表示形式（→ 表侧攻击）"——
        // 速攻魔法没有表示形式。盖牌变表侧 = 它被发动了，另有发动行/应答行承载
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.CardMoved(2, 2, DuelTimeline.Side.SELF, TestDuels.ENGAGE,
                        ZoneRef.spellTrap(PlayerSide.SELF, 0), ZoneRef.spellTrap(PlayerSide.SELF, 0),
                        "FACE_UP_ATTACK"));

        assertTrue(sections.lines(timeline.turn(2)).size() == 1,
                "只留回合边界行，翻面不该自成一行: " + joined(sections.lines(timeline.turn(2))));
        assertFalse(joined(sections.lines(timeline.turn(2))).contains("改变表示形式"),
                "魔陷没有表示形式: " + joined(sections.lines(timeline.turn(2))));
    }

    @Test
    void backrowTurnedFaceDownIsReported() {
        // 表侧 → 里侧是真发生了什么（效果盖回去），不能吞掉，否则模型以为它还开着
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.CardMoved(2, 2, DuelTimeline.Side.OPPONENT, TestDuels.SERVANT,
                        ZoneRef.spellTrap(PlayerSide.OPPONENT, 1), ZoneRef.spellTrap(PlayerSide.OPPONENT, 1),
                        "FACE_DOWN_DEFENSE"));

        String section = joined(sections.lines(timeline.turn(2)));
        assertTrue(section.contains("对方：王之仆人-黑魔术师 被翻回里侧"), section);
    }

    @Test
    void monsterRepositionStillSaysChangeOfPosition() {
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.SELF, TestDuels.SERVANT,
                        ZoneRef.mainMonster(PlayerSide.SELF, 2), ZoneRef.mainMonster(PlayerSide.SELF, 2),
                        "FACE_UP_DEFENSE"));

        assertTrue(joined(sections.lines(timeline.turn(3))).contains("改变表示形式（→ 表侧守备）"),
                joined(sections.lines(timeline.turn(3))));
    }

    @Test
    void searchedCardIsNamedEvenThoughTheRevealArrivesLate() {
        // 实机顺序：进手牌的 MOVE 卡号被抹零 → 晚一两帧才到 CONFIRM_CARDS（带真卡号）。
        // 事件是追加的，所以渲染层要把后面那条亮牌并回前面那条"加入手牌"。
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.CardMoved(2, 3, DuelTimeline.Side.OPPONENT, 0,
                        null, ZoneRef.hand(PlayerSide.OPPONENT), null),
                new DuelTimeline.Entry.CardRevealed(3, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT));

        String section = joined(sections.lines(timeline.turn(3)));

        assertTrue(section.contains("对方：王之仆人-黑魔术师 加入手牌"), "要认得出检索的卡:\n" + section);
        assertFalse(section.contains("内容不可见"), "亮牌到了就不该再写不可见:\n" + section);
        assertFalse(section.contains("公开手牌"), "并进「加入手牌」后不再单列:\n" + section);
    }

    @Test
    void revealWithoutAHandAddIsStillReported() {
        // 没配上"加入手牌"的亮牌（例如效果确认手牌）：单列，但仍是事实
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.CardRevealed(2, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT));

        assertTrue(joined(sections.lines(timeline.turn(3)))
                .contains("对方：公开 王之仆人-黑魔术师"), joined(sections.lines(timeline.turn(3))));
    }

    @Test
    void chainReadingOrderIsExplicit() {
        // 2026-11-14 用户要求按 MDPRO3 的写法："谁发动了效果 / 谁的效果处理了 / 处理结束"
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.EffectActivated(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, TestDuels.ENGAGE * 16, 1),
                new DuelTimeline.Entry.EffectActivated(3, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1, 2),
                new DuelTimeline.Entry.ChainSolving(4, 3, 2, TestDuels.SERVANT),
                new DuelTimeline.Entry.ChainSolving(5, 3, 1, TestDuels.ENGAGE),
                new DuelTimeline.Entry.ChainEnded(6, 3, 2));

        String section = joined(sections.lines(timeline.turn(3)));

        assertTrue(section.contains("我方：发动 闪刀起动-交闪"), "第 1 环不标序号，就是普通发动:\n" + section);
        assertTrue(section.contains("〔连锁 2〕对方：发动 王之仆人-黑魔术师"), "第 2 环要标出在第几环:\n" + section);
        assertTrue(section.contains("〔连锁 2 效果处理〕王之仆人-黑魔术师"), "要写清这一环是谁在处理:\n" + section);
        assertTrue(section.contains("〔连锁 1 效果处理〕闪刀起动-交闪"), section);
        assertTrue(section.contains("〔处理结束〕"), "整条连锁结束要有收尾:\n" + section);
        assertTrue(section.indexOf("〔连锁 2 效果处理〕") < section.indexOf("〔连锁 1 效果处理〕"),
                "后发的先结算，顺序不能倒:\n" + section);
    }

    @Test
    void negatedChainIsRecordedAsEffectNotResolved() {
        // 实机（MDPRO3 对局记录）：名推理 被连锁上的灰流丽无效——"效果无效"这一行必须进记录，
        // 否则模型会以为对方的检索/破坏照常结算了
        DuelTimeline timeline = timeline(
                new DuelTimeline.Entry.TurnStarted(1, 3, DuelTimeline.Side.SELF),
                new DuelTimeline.Entry.EffectActivated(2, 3, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, TestDuels.ENGAGE * 16, 1),
                new DuelTimeline.Entry.EffectActivated(3, 3, DuelTimeline.Side.OPPONENT,
                        TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1, 2),
                new DuelTimeline.Entry.ChainSolving(4, 3, 2, TestDuels.SERVANT),
                new DuelTimeline.Entry.ChainNegated(5, 3, 1, TestDuels.ENGAGE),
                new DuelTimeline.Entry.ChainSolving(6, 3, 1, TestDuels.ENGAGE),
                new DuelTimeline.Entry.ChainEnded(7, 3, 2));

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("〔连锁 1 效果无效〕闪刀起动-交闪"), "被无效的那一环要写明:\n" + section);
        assertTrue(section.contains("〔连锁 1 效果处理〕闪刀起动-交闪"), "无效仍占处理分界:\n" + section);
        assertTrue(section.contains("〔处理结束〕"), section);
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

        String section = joined(sections.lines(timeline.turn(3)));
        assertTrue(section.contains("对方：有卡加入手牌（内容不可见） ×3"), section);
    }

    @Test
    void blankTimelineRendersNothing() {
        assertTrue(sections.lines(timeline().turn(1)).isEmpty());
        assertTrue(sections.lines(timeline().turn(1)).isEmpty());
    }
}
