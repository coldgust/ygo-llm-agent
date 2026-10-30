package com.github.coldgust.ygodomain.zone;

import com.github.coldgust.ygodomain.duel.PlayerSide;

/**
 * 一个具体的区域格子。
 *
 * @param kind     区域种类
 * @param owner    区域归属方（相对视角：SELF / OPPONENT）
 * @param sequence 格内序号：主怪兽区 0–4；额外怪兽区 0–1（0 = 本方使用的那个，1 = 对方使用的那个）；
 *                 魔陷区 0–4；灵摆区 0（左）/ 1（右）；场地 0；其余列表区域为 0
 */
public record ZoneRef(ZoneKind kind, PlayerSide owner, int sequence) {

    public ZoneRef {
        if (owner == null) {
            throw new IllegalArgumentException("owner 不能为空");
        }
    }

    public static ZoneRef mainMonster(PlayerSide owner, int sequence) {
        return new ZoneRef(ZoneKind.MAIN_MONSTER_ZONE, owner, sequence);
    }

    public static ZoneRef extraMonster(PlayerSide owner) {
        return new ZoneRef(ZoneKind.EXTRA_MONSTER_ZONE, owner, 0);
    }

    public static ZoneRef spellTrap(PlayerSide owner, int sequence) {
        return new ZoneRef(ZoneKind.SPELL_TRAP_ZONE, owner, sequence);
    }

    public static ZoneRef pendulum(PlayerSide owner, int sequence) {
        return new ZoneRef(ZoneKind.PENDULUM_ZONE, owner, sequence);
    }

    public static ZoneRef field(PlayerSide owner) {
        return new ZoneRef(ZoneKind.FIELD_ZONE, owner, 0);
    }

    public static ZoneRef hand(PlayerSide owner) {
        return new ZoneRef(ZoneKind.HAND, owner, 0);
    }

    public static ZoneRef graveyard(PlayerSide owner) {
        return new ZoneRef(ZoneKind.GRAVEYARD, owner, 0);
    }

    public static ZoneRef banished(PlayerSide owner) {
        return new ZoneRef(ZoneKind.BANISHED, owner, 0);
    }

    public static ZoneRef deck(PlayerSide owner) {
        return new ZoneRef(ZoneKind.DECK, owner, 0);
    }

    public static ZoneRef extraDeck(PlayerSide owner) {
        return new ZoneRef(ZoneKind.EXTRA_DECK, owner, 0);
    }
}
