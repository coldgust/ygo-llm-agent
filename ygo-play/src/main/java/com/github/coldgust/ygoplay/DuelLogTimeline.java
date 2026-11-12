package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.context.DuelTimeline;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import com.github.coldgust.ygoduellog.query.DuelLog;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DuelLog} → 决策上下文记录端口（{@link DuelTimeline}）的适配器。
 *
 * <p>core 不依赖 ygo-duel-log，由本类（组装根侧）把事件流翻译成条目：
 * 映射一一对应，未列出的种类（状态快照 / 原始帧 / 决策上下文 / 终局）对
 * 上下文没有信息量，丢弃。区域串是 {@code DuelLogBridge#zoneOf} 产生的格式
 * （{@code SELF:SPELL_TRAP_ZONE#2}），在这里解回 {@link ZoneRef}。
 */
public final class DuelLogTimeline implements DuelTimeline {

    private final DuelLog log;
    /** 观察者绝对座位号缓存：DuelLog 没有直接 getter，从 duelInfo 取一次。 */
    private volatile int selfSeat = -1;

    public DuelLogTimeline(DuelLog log) {
        this.log = log;
    }

    @Override
    public List<Entry> since(long seq) {
        return map(log.since(seq));
    }

    @Override
    public List<Entry> turn(int turnNumber) {
        return map(log.turn(turnNumber));
    }

    @Override
    public long latestSeq() {
        return log.latestSeq();
    }


    private List<Entry> map(List<DuelEvent> events) {
        List<Entry> out = new ArrayList<>(events.size());
        for (DuelEvent event : events) {
            Entry entry = map(event);
            if (entry != null) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    private Entry map(DuelEvent event) {
        return switch (event.payload()) {
            case DuelEventPayload.TurnStarted t -> new Entry.TurnStarted(
                    event.seq(), event.turn(), sideOf(t.player()));
            case DuelEventPayload.PhaseEntered p -> new Entry.PhaseEntered(
                    event.seq(), event.turn(), Side.SYSTEM, p.phase());
            case DuelEventPayload.CardMoved m -> new Entry.CardMoved(
                    event.seq(), event.turn(), sideOfZone(m.fromZone(), m.toZone()),
                    m.passcode(), zone(m.fromZone()), zone(m.toZone()), m.toPosition());
            case DuelEventPayload.EffectActivated e -> new Entry.EffectActivated(
                    event.seq(), event.turn(), sideOf(e.player()), e.passcode(), e.desc());
            case DuelEventPayload.AttackDeclared a -> new Entry.AttackDeclared(
                    event.seq(), event.turn(), sideOf(a.player()), a.attackerPasscode(), a.target());
            case DuelEventPayload.LpChanged lp -> new Entry.LpChanged(
                    event.seq(), event.turn(), sideOf(lp.player()), lp.delta(), lp.lpAfter());
            case DuelEventPayload.DecisionMade d -> new Entry.DecisionMade(
                    event.seq(), event.turn(), sideOf(d.player()), d.requestType(),
                    d.selectionSummary(), d.source());
            default -> null;
        };
    }


    private Side sideOf(String player) {
        int seat = seatOf(player);
        return seat < 0 ? Side.SYSTEM
                : (seat == selfSeat() ? Side.SELF : Side.OPPONENT);
    }

    /** 卡移动的动作方取区域归属（协议事件不带玩家字段）；两组区域都空才退回 SYSTEM。 */
    private static Side sideOfZone(String fromZone, String toZone) {
        String zone = toZone != null ? toZone : fromZone;
        if (zone == null) {
            return Side.SYSTEM;
        }
        return zone.startsWith("SELF:") ? Side.SELF : Side.OPPONENT;
    }

    private int selfSeat() {
        int seat = selfSeat;
        if (seat < 0) {
            seat = log.duelInfo().selfSeat();
            if (seat >= 0) {
                // -1（开局前）不缓存：否则此后所有条目都会被误标成 SYSTEM
                selfSeat = seat;
            }
        }
        return seat;
    }

    private static int seatOf(String player) {
        if (player == null || !player.startsWith("P")) {
            return -1;
        }
        try {
            return Integer.parseInt(player.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ZoneRef zone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            PlayerSide owner = "SELF".equals(raw.substring(0, colon))
                    ? PlayerSide.SELF : PlayerSide.OPPONENT;
            String kindText = raw.substring(colon + 1);
            int hash = kindText.indexOf('#');
            String kindName = hash >= 0 ? kindText.substring(0, hash) : kindText;
            int sequence = hash >= 0 ? Integer.parseInt(kindText.substring(hash + 1)) : 0;
            return new ZoneRef(ZoneKind.valueOf(kindName), owner, sequence);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
