package com.github.coldgust.ygodomain.duel;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * 战场镜像的不可变快照（我方相对视角）。
 *
 * <p>每次状态事件后由 SDK 产出新的实例；所有对象均为 record，
 * equals/hashCode 稳定，可直接用于比较与去重。
 *
 * <p>视角约定：本快照中的 SELF = 观察者（即将成为决策者的那名玩家），
 * OPPONENT = 对方。协议中被裁剪的信息一律以 {@code CardIdentity.Unknown} 表达。
 *
 * @param duelRule     大师规则版本（协议 duel_rule，5 = MR5）
 * @param turnNumber   当前回合数（从 1 起）
 * @param players      双方状态（key = 相对视角）
 * @param cards        全部在场卡实例（key = instanceId）
 * @param currentChain 当前连锁（index 1 = 最先发动）
 * @param result       对局结果；未结束为 null
 * @param selfSeat     观察者的绝对座位号
 */
public record DuelState(
        int duelRule,
        int turnNumber,
        PlayerSide turnPlayer,
        Phase phase,
        Map<PlayerSide, PlayerState> players,
        Map<Long, CardInstance> cards,
        List<ChainNode> currentChain,
        DuelResult result,
        int selfSeat
) {
    public DuelState {
        players = players == null ? Map.of() : Map.copyOf(players);
        cards = cards == null ? Map.of() : Map.copyOf(cards);
        currentChain = currentChain == null ? List.of() : List.copyOf(currentChain);
    }


    public PlayerState player(PlayerSide side) {
        return players.get(side);
    }

    public PlayerState self() {
        return player(PlayerSide.SELF);
    }

    public PlayerState opponent() {
        return player(PlayerSide.OPPONENT);
    }

    public boolean isMyTurn() {
        return turnPlayer == PlayerSide.SELF;
    }

    public boolean isFinished() {
        return result != null;
    }


    public Optional<CardInstance> card(long instanceId) {
        return Optional.ofNullable(cards.get(instanceId));
    }

    /** 某个区域格子上的卡（至多一张）。 */
    public Optional<CardInstance> cardAt(ZoneRef zone) {
        return cards.values().stream()
                .filter(c -> zone.equals(c.zone()))
                .findFirst();
    }

    /**
     * 某方某区域中的全部卡（列表区域按放置顺序）。
     */
    public List<CardInstance> cardsIn(ZoneKind kind, PlayerSide owner) {
        return slotDeduped(List.of(kind), owner);
    }

    /** 某方的全部怪兽（主怪兽区 + 额外怪兽区）。 */
    public List<CardInstance> monsters(PlayerSide side) {
        return slotDeduped(List.of(ZoneKind.MAIN_MONSTER_ZONE, ZoneKind.EXTRA_MONSTER_ZONE), side);
    }

    /** 某方的全部魔法陷阱（魔陷区；灵摆区用 {@link #pendulumZones}，场地魔法用 {@link #fieldSpell}）。 */
    public List<CardInstance> spellTraps(PlayerSide side) {
        return slotDeduped(List.of(ZoneKind.SPELL_TRAP_ZONE), side);
    }

    /** 按 (区域, 槽序) 去重后取卡，按槽序升序。 */
    private List<CardInstance> slotDeduped(List<ZoneKind> kinds, PlayerSide owner) {
        Map<String, CardInstance> bySlot = new TreeMap<>();
        for (CardInstance c : cards.values()) {
            ZoneRef zone = c.zone();
            if (zone == null || zone.owner() != owner || !kinds.contains(zone.kind())) {
                continue;
            }
            String slot = zone.kind().name() + "#" + zone.sequence();
            bySlot.merge(slot, c, DuelState::richer);
        }
        return List.copyOf(bySlot.values());
    }

    /** 同一槽位的重复实例里取信息更全的那个：优先身份已知，其次实例号更大。 */
    private static CardInstance richer(CardInstance a, CardInstance b) {
        if (a.isUnknown() != b.isUnknown()) {
            return a.isUnknown() ? b : a;
        }
        return a.instanceId() >= b.instanceId() ? a : b;
    }

    public Optional<CardInstance> fieldSpell(PlayerSide side) {
        return cardAt(ZoneRef.field(side));
    }

    /** 某方的灵摆区（0 = 左，1 = 右）。 */
    public List<CardInstance> pendulumZones(PlayerSide side) {
        return cardsIn(ZoneKind.PENDULUM_ZONE, side);
    }

    /** 某方怪兽区的空格子（含额外怪兽区的占用判定，粗粒度）。 */
    public List<Integer> freeMainMonsterZones(PlayerSide side) {
        var occupied = cardsIn(ZoneKind.MAIN_MONSTER_ZONE, side).stream()
                .mapToInt(c -> c.zone().sequence()).boxed().toList();
        return IntStream.rangeClosed(0, 4)
                .filter(s -> !occupied.contains(s))
                .boxed().toList();
    }
}
