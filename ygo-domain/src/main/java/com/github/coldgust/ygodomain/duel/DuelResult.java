package com.github.coldgust.ygodomain.duel;

/**
 * 对局结果。
 *
 * @param winner 胜方；平局为 null
 * @param reason 结束原因
 */
public record DuelResult(PlayerSide winner, WinReason reason) {

    public boolean isDraw() {
        return winner == null;
    }
}
