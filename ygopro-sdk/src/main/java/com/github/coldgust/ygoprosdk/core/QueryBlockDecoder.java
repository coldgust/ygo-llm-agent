package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.card.CardDefinition;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;

/**
 * 查询块解码（规范 §9.2 / §9.4.3 / §9.4.4）：
 * {@code [u32 块长][u32 flags][按 flags 位序的字段值]}，值不带各自的 flag 标签。
 */
public final class QueryBlockDecoder {

    public record CardBlock(int length, int flags, long code, Integer position) {
    }

    /* 查询标志位（规范 §9.2，写出顺序 = 位序） */
    private static final int F_CODE = 0x000001;
    private static final int F_POSITION = 0x000002;
    private static final int F_ALIAS = 0x000004;
    private static final int F_TYPE = 0x000008;
    private static final int F_LEVEL = 0x000010;
    private static final int F_RANK = 0x000020;
    private static final int F_ATTRIBUTE = 0x000040;
    private static final int F_RACE = 0x000080;
    private static final int F_ATTACK = 0x000100;
    private static final int F_DEFENSE = 0x000200;
    private static final int F_BASE_ATTACK = 0x000400;
    private static final int F_BASE_DEFENSE = 0x000800;
    private static final int F_REASON = 0x001000;
    private static final int F_REASON_CARD = 0x002000;
    private static final int F_EQUIP_CARD = 0x004000;
    private static final int F_TARGET_CARD = 0x008000;
    private static final int F_OVERLAY = 0x010000;
    private static final int F_COUNTERS = 0x020000;
    private static final int F_OWNER = 0x040000;
    private static final int F_STATUS = 0x080000;
    private static final int F_LSCALE = 0x200000;
    private static final int F_RSCALE = 0x400000;
    private static final int F_LINK = 0x800000;

    private QueryBlockDecoder() {
    }

    /**
     * 从游标处解码一个卡块；返回 null 表示"空槽位"或数据不足。
     * 块长含长度字段与 flags 字段自身；调用方以块长推进游标。
     */
    public static CardBlock decode(ByteReader r) {
        if (r.remaining() < 4) {
            return null;
        }
        int length = r.i32();
        if (length <= 8 || r.remaining() < 4) {
            return null; // 空槽位（引擎对空位写 LEN_EMPTY）
        }
        return decodeBody(r, length);
    }

    /**
     * 解码块体：游标需已越过 {@code u32 块长}、停在 {@code flags} 处。
     *
     * <p>调用方自己读过块长时用这个重载——避免"块长被读两次"导致后续块整体错位
     * （错位后位置打包字会落到卡号的位置，卡名就变成 0x0A000200 这类垃圾值）。
     */
    public static CardBlock decodeBody(ByteReader r, int length) {
        if (r.remaining() < 4) {
            return null;
        }
        int flags = r.i32();
        long code = 0;
        Integer position = null;
        if ((flags & F_CODE) != 0 && r.remaining() >= 4) {
            code = r.u32();
        }
        if ((flags & F_POSITION) != 0 && r.remaining() >= 4) {
            int packed = r.i32();
            position = (packed >>> 24) & 0xFF; // 打包位置字的最高字节 = 表示形式
        }
        skipFields(r, flags);
        return new CardBlock(length, flags, code, position);
    }

    /** 由块内 code 构造身份（code==0 = 未知卡）。 */
    public static CardIdentity identity(long code, CardInfoLookup resolver) {
        if (code == 0) {
            return CardIdentity.UNKNOWN;
        }
        return new CardIdentity.Known(code, resolver == null ? null : resolver.definition(code));
    }

    /** 卡定义查询函数（桥接 {@link CardInfoResolver}）。 */
    @FunctionalInterface
    public interface CardInfoLookup {
        CardDefinition definition(long passcode);
    }

    private static void skipFields(ByteReader r, int flags) {
        int[] fixedFields = {F_ALIAS, F_TYPE, F_LEVEL, F_RANK, F_ATTRIBUTE, F_RACE,
                F_ATTACK, F_DEFENSE, F_BASE_ATTACK, F_BASE_DEFENSE, F_REASON, F_REASON_CARD,
                F_EQUIP_CARD, F_TARGET_CARD, F_OWNER, F_STATUS, F_LSCALE, F_RSCALE, F_LINK};
        for (int f : fixedFields) {
            if ((flags & f) != 0 && r.remaining() >= 4) {
                r.skip(4);
            }
        }
        if ((flags & F_OVERLAY) != 0 && r.remaining() >= 4) {
            int count = r.i32();
            r.skip(4 * count);
        }
        if ((flags & F_COUNTERS) != 0 && r.remaining() >= 4) {
            int count = r.i32();
            r.skip(4 * count);
        }
    }
}
