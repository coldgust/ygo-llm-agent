package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.duel.DuelResult;
import java.nio.file.Path;

/** 一局结束后的汇总（CLI 打印 / HTTP 查询共用）。 */
public record DuelRunResult(
        RunStats stats,
        DuelResult duelResult,
        Path tracePath,
        Path duelLogPath) {
}
