package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决斗记录流的写入语义（docs/planner-judge-memory-redesign.md §5，2026-11-14 修订）：
 * **纯拉取式**——记录 = 引擎时间线事件，逐事件原文、RECORD 永不修剪。
 * 决策确认信息不进记录流（2026-11-14 用户指令）：规划器只要对局记录；
 * 决策器自己的 AI 回答（choice/rationale）已经在它的对话里，不重复。
 */
class DuelRecordFeedTest {

    private final CardFacts facts = TestDuels.facts();

    private static DuelChatMemory memory(String id) {
        return DuelChatMemory.forJudge(id, SystemMessage.from("系统提示"), null);
    }

    /** 静态假端口：条目一次性给全，按 seq/turn 过滤。 */
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

    private static String texts(DuelChatMemory memory) {
        return String.join("\n||\n", memory.messages().stream()
                .map(m -> m instanceof SystemMessage s ? s.text()
                        : m instanceof dev.langchain4j.data.message.UserMessage u ? u.singleText()
                        : ((dev.langchain4j.data.message.AiMessage) m).text())
                .toList());
    }

    @Test
    void ourOwnActivationIsCarriedByTheEngineLine() {
        // 决策确认行已取消；我方发动的事实由引擎事件行承载（带条款正文，firedClause 兜底）
        List<DuelTimeline.Entry> entries = List.of(
                new DuelTimeline.Entry.CardMoved(1, 2, DuelTimeline.Side.SELF, TestDuels.ENGAGE,
                        ZoneRef.hand(PlayerSide.SELF), ZoneRef.spellTrap(PlayerSide.SELF, 0),
                        "FACE_UP_ATTACK"),
                new DuelTimeline.Entry.EffectActivated(2, 2, DuelTimeline.Side.SELF,
                        TestDuels.ENGAGE, TestDuels.ENGAGE * 16, 1));
        DuelRecordFeed feed = new DuelRecordFeed(timelineOf(entries), facts);
        DuelChatMemory judge = memory("j");
        feed.attach(judge);
        feed.fillBeforeCall(judge, 2);

        String seen = texts(judge);
        assertTrue(seen.contains("我方：发动 闪刀起动-交闪"), "我方发动进记录（引擎行）: " + seen);
        assertTrue(seen.contains("交闪 上场（我方魔陷区1）"), "落点行要保留: " + seen);
    }

    @Test
    void plannerAndJudgeShareTheSameRecordStream() {
        // 2026-11-14 用户指令：规划器只要对局记录，决策器也一样——同一条记录流，各自独立水位
        DuelTimeline timeline = timelineOf(List.of(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.LpChanged(2, 2, DuelTimeline.Side.SELF, -2300, 5700)));
        DuelRecordFeed feed = new DuelRecordFeed(timeline, facts);
        DuelChatMemory planner = memory("p");
        DuelChatMemory judge = memory("j");
        feed.attach(planner);
        feed.attach(judge);

        feed.fillBeforeCall(planner, 2);
        feed.fillBeforeCall(judge, 2);

        assertEquals(texts(planner), texts(judge), "两边拿到的是同一条记录流");
        assertTrue(texts(planner).contains("〔决斗记录〕"));
        assertTrue(texts(planner).contains("我方 LP -2300（→5700）"));
    }

    @Test
    void pullAppendsOpponentIncrementsAndAdvancesWatermarkPerMemory() {
        DuelTimeline timeline = timelineOf(List.of(
                new DuelTimeline.Entry.TurnStarted(1, 2, DuelTimeline.Side.OPPONENT),
                new DuelTimeline.Entry.LpChanged(2, 2, DuelTimeline.Side.SELF, -2300, 5700)));
        DuelRecordFeed feed = new DuelRecordFeed(timeline, facts);
        DuelChatMemory judge = memory("j");
        feed.attach(judge);

        feed.fillBeforeCall(judge, 2);
        String seen = texts(judge);
        assertTrue(seen.contains("〔第 2 回合 · 对方回合〕"), "回合边界行入史: " + seen);
        assertTrue(seen.contains("我方 LP -2300（→5700）"), "对手/系统增量入史: " + seen);

        // 再次拉取：水位已推进，不重复入史
        feed.fillBeforeCall(judge, 2);
        assertEquals(texts(judge), seen, "重答属同一次使用，不重复拉");
    }

    @Test
    void fullHistoryAccumulatesAcrossTurnsWithoutTrim() {
        // 直推全文的核心约定：记录行（RECORD）永不修剪——第 5 回合仍看得到第 1 回合的事件
        List<DuelTimeline.Entry> entries = new java.util.ArrayList<>();
        for (int turn = 1; turn <= 4; turn++) {
            entries.add(new DuelTimeline.Entry.TurnStarted(entries.size() + 1, turn,
                    turn % 2 == 1 ? DuelTimeline.Side.SELF : DuelTimeline.Side.OPPONENT));
            entries.add(new DuelTimeline.Entry.LpChanged(entries.size() + 2, turn,
                    DuelTimeline.Side.OPPONENT, -300, 8000 - 300 * turn));
        }
        DuelRecordFeed feed = new DuelRecordFeed(timelineOf(entries), facts);
        DuelChatMemory judge = new DuelChatMemory("j", 2, 1,
                SystemMessage.from("系统提示"), null); // NORMAL 窗口极小，逼出修剪
        feed.attach(judge);

        for (int t = 1; t <= 5; t++) {
            feed.fillBeforeCall(judge, t);
        }
        String seen = texts(judge);
        assertTrue(seen.contains("〔第 1 回合 · 我方回合〕"), "第 1 回合边界仍在（永不修剪）: " + seen);
        assertTrue(seen.contains("〔第 4 回合 · 对方回合〕"), "最近回合也在: " + seen);
    }

    @Test
    void unattachedMemoryIsIgnored() {
        DuelRecordFeed feed = new DuelRecordFeed(null, facts);
        DuelChatMemory stranger = memory("s");
        feed.fillBeforeCall(stranger, 3); // 不抛、不写入
        assertEquals(1, stranger.messages().size(), "只有 pinned system");
    }
}
