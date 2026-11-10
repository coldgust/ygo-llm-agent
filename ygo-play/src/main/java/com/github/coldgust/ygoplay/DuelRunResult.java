package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.duel.DuelResult;

public record DuelRunResult(
        RunStats stats,
        DuelResult duelResult) {
}
