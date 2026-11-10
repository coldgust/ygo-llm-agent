package com.github.coldgust.ygoduellog.query;

import com.github.coldgust.ygoduellog.event.DuelEventPayload;

import java.util.List;

public record DuelInfo(
        DuelEventPayload.DuelStarted started,
        int selfSeat,
        int totalTurns,
        boolean finished,
        String winner,
        String endReason
) {
    public List<DuelEventPayload.PlayerInfo> players() {
        return started == null ? List.of() : started.players();
    }
}
