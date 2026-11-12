package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.context.DuelTimeline;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygoduellog.InMemoryDuelLog;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DuelLog → DuelTimeline 的映射：绝对座位要换成相对视角，区域串要解回 ZoneRef；
 * 对上下文无信息量的种类（快照/原始帧等）不出现在条目里。
 */
class DuelLogTimelineTest {

    private static final long ENGAGE = 25048511L;

    @Test
    void mapsEventStreamToRelativeEntries() {
        InMemoryDuelLog log = new InMemoryDuelLog();
        DuelLogTimeline timeline = new DuelLogTimeline(log);
        log.markSelfSeat(0);
        log.updateContext(3, "MAIN_PHASE_1", System.currentTimeMillis());
        log.append("SYSTEM", DuelEventKind.TURN_STARTED, new DuelEventPayload.TurnStarted("P0", 3));
        log.append("P1", DuelEventKind.EFFECT_ACTIVATED,
                new DuelEventPayload.EffectActivated("P1", ENGAGE, ENGAGE * 16));
        log.append("P0", DuelEventKind.LP_CHANGED,
                new DuelEventPayload.LpChanged("P0", -2300, 5700, null));
        // 快照与原始帧对上下文无信息量：必须被映射丢弃
        log.append("SYSTEM", DuelEventKind.STATE_SNAPSHOT, new DuelEventPayload.StateSnapshot(null));
        log.awaitDrain();

        List<DuelTimeline.Entry> entries = timeline.turn(3);
        assertEquals(3, entries.size());
        assertInstanceOf(DuelTimeline.Entry.TurnStarted.class, entries.get(0));
        DuelTimeline.Entry.EffectActivated effect =
                (DuelTimeline.Entry.EffectActivated) entries.get(1);
        assertEquals(DuelTimeline.Side.OPPONENT, effect.side(), "P1 要换算成对方");
        assertEquals(ENGAGE, effect.passcode());
        assertEquals(DuelTimeline.Side.SELF, ((DuelTimeline.Entry.LpChanged) entries.get(2)).side());
        // 水位是日志的 seq，被映射丢弃的事件也占号——since() 的排他语义靠它保持单调
        assertEquals(4, timeline.latestSeq());

        assertEquals(2, timeline.since(1).size(), "增量拉取按 seq 排他");
        assertEquals(List.of(), timeline.turn(1), "别的回合拉不到");
    }

    @Test
    void cardMovedZonesAreParsedBackToZoneRefs() {
        InMemoryDuelLog log = new InMemoryDuelLog();
        DuelLogTimeline timeline = new DuelLogTimeline(log);
        log.markSelfSeat(1); // 我方坐 1 号位：SELF/OPP 前缀是相对视角，与座位无关
        log.updateContext(2, "MAIN_PHASE_1", System.currentTimeMillis());
        log.append("SYSTEM", DuelEventKind.CARD_MOVED, new DuelEventPayload.CardMoved(
                ENGAGE, String.valueOf(ENGAGE), "SELF:HAND", null,
                "SELF:MAIN_MONSTER_ZONE#2", "FACE_UP_ATTACK", null));
        log.append("SYSTEM", DuelEventKind.CARD_MOVED, new DuelEventPayload.CardMoved(
                0, "unknown", null, null,
                "OPP:SPELL_TRAP_ZONE#3", "FACE_DOWN_DEFENSE", null));
        log.awaitDrain();

        List<DuelTimeline.Entry> entries = timeline.since(0);
        assertEquals(2, entries.size());
        DuelTimeline.Entry.CardMoved summon = (DuelTimeline.Entry.CardMoved) entries.get(0);
        assertEquals(DuelTimeline.Side.SELF, summon.side());
        assertEquals(ZoneKind.MAIN_MONSTER_ZONE, summon.toZone().kind());
        assertEquals(2, summon.toZone().sequence(), "协议 0 起序号原样保留（+1 是渲染层 ZoneCodes 的事）");
        assertEquals("FACE_UP_ATTACK", summon.position());
        DuelTimeline.Entry.CardMoved set = (DuelTimeline.Entry.CardMoved) entries.get(1);
        assertEquals(DuelTimeline.Side.OPPONENT, set.side());
        assertTrue(set.passcode() == 0, "盖放内容不可见");
        assertEquals(ZoneKind.SPELL_TRAP_ZONE, set.toZone().kind());
    }
}
