package com.github.coldgust.ygoplay;

import java.nio.file.Path;

/**
 * 一局对局的输入：连接目标、卡组、归档产物路径与运行调参。
 *
 * @param tuning       运行调参（原始帧/看门狗/进房等待/combo 注入上限），null = 全默认
 */
public record DuelRunSpec(
        String host, int port, String name, String move,
        String deckYdkText, Path deckPath,
        String archetype, boolean comboRecord,
        Path duelLogPath, Path tracePath, String deckStrategy,
        DuelTuning tuning) {

    public DuelRunSpec {
        if (host == null || host.isBlank()) host = "127.0.0.1";
        if (port <= 0) port = 7911;
        if (name == null || name.isBlank()) name = "ygo-play-runner";
        if (move == null || move.isBlank()) move = "second";
        if (comboRecord) {
            move = "second"; // combo 录入：先手让给用户
        }
        tuning = tuning == null ? DuelTuning.defaults() : tuning;
    }

    /** 旧签名（运行调参走默认）。 */
    public DuelRunSpec(
            String host, int port, String name, String move,
            String deckYdkText, Path deckPath,
            String archetype, boolean comboRecord,
            Path duelLogPath, Path tracePath, String deckStrategy) {
        this(host, port, name, move, deckYdkText, deckPath,
                archetype, comboRecord, duelLogPath, tracePath, deckStrategy, null);
    }
}
