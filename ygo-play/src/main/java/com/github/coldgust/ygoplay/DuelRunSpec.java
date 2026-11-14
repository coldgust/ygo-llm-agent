package com.github.coldgust.ygoplay;

import java.nio.file.Path;

/**
 * 一局对局的输入：连接目标、卡组、归档产物路径与运行调参。
 *
 * @param tuning       运行调参（原始帧/看门狗/进房等待/combo 注入上限），必填（null 抛出）
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
        if (tuning == null) {
            throw new IllegalArgumentException("DuelRunSpec.tuning 未提供"
                    + "（运行调参由装配层解析传入：CLI .env 的 DUEL_CHOICE_TIMEOUT_MS/DUEL_START_WAIT_MS/COMBO_CONTEXT_LIMIT；"
                    + "server 的 ygo.play.*）");
        }
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
