package com.github.coldgust.ygoduellog.event;

import java.time.Instant;

public record DuelEvent(
        long seq,
        Instant wallClock,
        long gameClockMs,
        int turn,
        String phase,
        String actor,
        DuelEventKind kind,
        DuelEventPayload payload
) {
}
