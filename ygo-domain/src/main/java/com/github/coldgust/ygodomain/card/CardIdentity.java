package com.github.coldgust.ygodomain.card;

/**
 * 一张卡在镜像中的身份。
 *
 * <p>协议（服务端按接收者裁剪视野）会对不可见的卡下发 {@code code == 0}，
 * 规范将其定义为"未知卡"：张数、位置、表示形式可见，身份不可见。
 * 显式区分 {@link Known} 与 {@link Unknown} 使 Agent 可以把
 * "对手有 3 张未知手牌"作为可推理的事实，而不是缺失的数据。
 */
public sealed interface CardIdentity permits CardIdentity.Known, CardIdentity.Unknown {

    /**
     * 已知卡：携带 passcode；可选携带卡片定义。
     */
    record Known(long passcode, CardDefinition definition) implements CardIdentity {
        public Known {
            if (passcode == 0) {
                throw new IllegalArgumentException("passcode 0 表示未知卡，请使用 Unknown");
            }
        }

        public static Known of(long passcode) {
            return new Known(passcode, null);
        }

        public static Known of(CardDefinition definition) {
            return new Known(definition.passcode(), definition);
        }
    }

    /**
     * 未知卡（协议下发 code == 0）：只有存在性与位置信息。
     */
    enum Unknown implements CardIdentity {
        INSTANCE;

        @Override
        public String toString() {
            return "UnknownCard";
        }
    }

    CardIdentity UNKNOWN = Unknown.INSTANCE;

    /**
     * 返回 passcode；未知卡返回 0。
     */
    default long passcode() {
        return this instanceof Known k ? k.passcode() : 0;
    }

    /**
     * 返回卡片定义（可能为空：调用方未注入卡库）。
     */
    default CardDefinition definition() {
        return this instanceof Known k ? k.definition() : null;
    }

    default boolean isUnknown() {
        return this == UNKNOWN;
    }
}
