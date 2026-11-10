package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.duel.DuelResult;
import java.nio.file.Path;

public record DuelRunResult(
        RunStats stats,
        DuelResult duelResult,
        Path tracePath,
        Path duelLogPath) {
}
