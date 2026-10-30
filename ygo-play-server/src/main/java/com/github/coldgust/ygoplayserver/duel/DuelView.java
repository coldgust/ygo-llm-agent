package com.github.coldgust.ygoplayserver.duel;

import java.time.Instant;

/** 一局的状态视图（HTTP 响应）。 */
public record DuelView(
        String id,
        String status,
        String host,
        int port,
        String name,
        String move,
        String deckSource,
        String archetype,
        Instant startedAt,
        Instant finishedAt,
        Integer answered,
        Integer fallbacks,
        String winner,
        String reason,
        String error,
        String tracePath,
        String duelLogPath,
        /** 入库策略（ask/auto/always/never），见 {@link DuelRequest}。 */
        String archivePolicy,
        /** 入库决定状态：ask 模式下 PENDING（等用户决定）→ ACCEPTED / DECLINED；其余模式为 null。 */
        String archiveState) {
}
