package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

/**
 * 打包位置字 ↔ 域区域 的双向映射（规范 §9.2 + §8.5 位图）。
 *
 * <p>引擎槽位布局：mzone 7 槽（0–4 主怪兽区，5/6 额外怪兽区）、
 * szone 8 槽（0–4 魔陷，5 保留，6/7 灵摆）、场地魔法在独立 LOCATION_FZONE。
 * 域对象 ExtraMonsterZone / PendulumZone 的 sequence 为本方视角槽序（0/1）。
 */
public final class ZoneMapper {
    private ZoneMapper() {
    }

    public static final int LOC_DECK = 0x01;
    public static final int LOC_HAND = 0x02;
    public static final int LOC_MZONE = 0x04;
    public static final int LOC_SZONE = 0x08;
    public static final int LOC_GRAVE = 0x10;
    public static final int LOC_REMOVED = 0x20;
    public static final int LOC_EXTRA = 0x40;
    public static final int LOC_OVERLAY = 0x80;
    public static final int LOC_FZONE = 0x100;

    public static final int POS_FACEUP_ATTACK = 0x1;
    public static final int POS_FACEDOWN_ATTACK = 0x2;
    public static final int POS_FACEUP_DEFENSE = 0x4;
    public static final int POS_FACEDOWN_DEFENSE = 0x8;


    /** 解包：c | l<<8 | s<<16 | pos<<24。 */
    public record Packed(int controller, int location, int sequence, int position) {
    }

    public static Packed unpack(long packed) {
        long v = packed & 0xFFFFFFFFL;
        return new Packed((int) (v & 0xFF), (int) ((v >>> 8) & 0xFF),
                (int) ((v >>> 16) & 0xFF), (int) ((v >>> 24) & 0xFF));
    }

    public static long pack(int controller, int location, int sequence, int position) {
        return (controller & 0xFFL)
                | ((location & 0xFFL) << 8)
                | ((sequence & 0xFFL) << 16)
                | ((position & 0xFFL) << 24);
    }


    /**
     * 协议 (location, sequence) → 域 ZoneKind + 域 sequence；无法映射返回 null。
     */
    public static ZoneRef toZone(int absSeat, int location, int sequence, PlayerSide owner) {
        return switch (location) {
            case LOC_HAND -> ZoneRef.hand(owner);
            case LOC_DECK -> ZoneRef.deck(owner);
            case LOC_GRAVE -> ZoneRef.graveyard(owner);
            case LOC_REMOVED -> ZoneRef.banished(owner);
            case LOC_EXTRA -> ZoneRef.extraDeck(owner);
            case LOC_FZONE -> ZoneRef.field(owner);
            case LOC_MZONE -> sequence <= 4
                    ? ZoneRef.mainMonster(owner, sequence)
                    : new ZoneRef(ZoneKind.EXTRA_MONSTER_ZONE, owner, sequence - 5);
            case LOC_SZONE -> {
                if (sequence <= 4) {
                    yield ZoneRef.spellTrap(owner, sequence);
                }
                if (sequence == 6 || sequence == 7) {
                    yield ZoneRef.pendulum(owner, sequence - 6);
                }
                yield null; // szone seq 5：引擎保留
            }
            default -> null;
        };
    }

    /**
     * 域 ZoneRef → 协议 (location, sequence)。与 {@link #toZone} 互逆。
     */
    public record Slot(int location, int sequence) {
    }

    public static Slot toSlot(ZoneRef zone) {
        return switch (zone.kind()) {
            case DECK -> new Slot(LOC_DECK, 0);
            case HAND -> new Slot(LOC_HAND, 0);
            case GRAVEYARD -> new Slot(LOC_GRAVE, 0);
            case BANISHED -> new Slot(LOC_REMOVED, 0);
            case EXTRA_DECK -> new Slot(LOC_EXTRA, 0);
            case FIELD_ZONE -> new Slot(LOC_FZONE, 0);
            case MAIN_MONSTER_ZONE -> new Slot(LOC_MZONE, zone.sequence());
            case EXTRA_MONSTER_ZONE -> new Slot(LOC_MZONE, 5 + zone.sequence());
            case SPELL_TRAP_ZONE -> new Slot(LOC_SZONE, zone.sequence());
            case PENDULUM_ZONE -> new Slot(LOC_SZONE, 6 + zone.sequence());
        };
    }


    /**
     * 位图位 → 协议槽位（规范 §8.5）：怪兽区位 = seq（0–6，5/6 为额外怪兽区）；
     * <b>bit 7 = 场地魔法区</b>；魔陷区位 = 8 + seq（0–4 魔陷，14/15 灵摆）；高位 +16 为另一侧座位。
     */
    public static Slot slotFromBitmapBit(int bit) {
        int b = bit >= 16 ? bit - 16 : bit;
        if (b < 7) {
            return new Slot(LOC_MZONE, b);
        }
        if (b == 7) {
            return new Slot(LOC_FZONE, 0);
        }
        if (b <= 15) {
            return new Slot(LOC_SZONE, b - 8);
        }
        return null;
    }

    /** 位图位所属的绝对座位（0/1）。 */
    public static int seatFromBitmapBit(int bit) {
        return bit >= 16 ? 1 : 0;
    }

    /** 协议槽位 → 位图位（与 {@link #slotFromBitmapBit} 互逆）。 */
    public static int bitmapBit(int location, int sequence, boolean opponent) {
        int bit = switch (location) {
            case LOC_MZONE -> sequence;
            case LOC_FZONE -> 7;
            case LOC_SZONE -> 8 + sequence;
            default -> -1;
        };
        if (bit < 0) {
            return -1;
        }
        return opponent ? bit + 16 : bit;
    }


    public static Position toPosition(int posByte) {
        return switch (posByte) {
            case POS_FACEUP_ATTACK -> Position.FACE_UP_ATTACK;
            case POS_FACEDOWN_ATTACK -> Position.FACE_DOWN_ATTACK;
            case POS_FACEUP_DEFENSE -> Position.FACE_UP_DEFENSE;
            case POS_FACEDOWN_DEFENSE -> Position.FACE_DOWN_DEFENSE;
            // POS_FACEUP / POS_FACEDOWN 是魔陷用的"朝向组合位"（0x5 / 0xA）：
            // 魔陷没有攻守之分，归一到"表侧 / 里侧"，否则表侧魔陷会被当成立场未知。
            case POS_FACEUP_ATTACK | POS_FACEUP_DEFENSE ->
                    Position.FACE_UP_ATTACK;
            case POS_FACEDOWN_ATTACK | POS_FACEDOWN_DEFENSE ->
                    Position.FACE_DOWN_DEFENSE;
            default -> null;
        };
    }

    public static int fromPosition(Position position) {
        return switch (position) {
            case FACE_UP_ATTACK -> POS_FACEUP_ATTACK;
            case FACE_DOWN_ATTACK -> POS_FACEDOWN_ATTACK;
            case FACE_UP_DEFENSE -> POS_FACEUP_DEFENSE;
            case FACE_DOWN_DEFENSE -> POS_FACEDOWN_DEFENSE;
        };
    }
}
