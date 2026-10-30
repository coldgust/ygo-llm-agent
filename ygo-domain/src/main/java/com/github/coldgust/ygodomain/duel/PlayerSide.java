package com.github.coldgust.ygodomain.duel;

/**
 * 玩家相对视角。战场镜像以"我方 / 对方"为基准（与协议视野一致），
 * 绝对座位号仅在调试与回放时使用。
 */
public enum PlayerSide {
    SELF,
    OPPONENT;

    /** 取对侧。 */
    public PlayerSide opposite() {
        return this == SELF ? OPPONENT : SELF;
    }
}
