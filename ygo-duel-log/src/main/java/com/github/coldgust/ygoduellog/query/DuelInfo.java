package com.github.coldgust.ygoduellog.query;

import com.github.coldgust.ygoduellog.event.DuelEventPayload;

import java.util.List;

/**
 * 对局元信息。
 *
 * @param started    开局载荷（玩家/规则/计时）
 * @param selfSeat   记录视角方座位（-1 = 未标注）
 * @param totalTurns 已进行的回合数
 * @param finished   是否已结束
 * @param winner     "P0"/"P1"/null（未结束或平局）
 * @param endReason  结束原因（未结束为 null）
 */
public record DuelInfo(
        DuelEventPayload.DuelStarted started,
        int selfSeat,
        int totalTurns,
        boolean finished,
        String winner,
        String endReason
) {
    /** 双方玩家信息。 */
    public List<DuelEventPayload.PlayerInfo> players() {
        return started == null ? List.of() : started.players();
    }
}
