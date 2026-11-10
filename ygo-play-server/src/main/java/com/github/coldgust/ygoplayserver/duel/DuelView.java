package com.github.coldgust.ygoplayserver.duel;

import java.time.Instant;

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
        String archivePolicy,
        String archiveState) {
}
