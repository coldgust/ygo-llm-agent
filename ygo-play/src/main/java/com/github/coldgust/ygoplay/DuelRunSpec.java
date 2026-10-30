package com.github.coldgust.ygoplay;

import java.nio.file.Path;

/**
 * 启动一局所需的最小参数集（CLI 与 HTTP 服务共用）。
 *
 * @param archetype    卡组主题（= combo 库主题）。决定加载哪份 {@code data/combos/compiled/<主题>.json}
 * @param deckYdkText  卡组 .ydk 文本（优先）；为空时读 deckPath
 * @param deckStrategy 用户手写的本卡组打法思路（注入 LLM 上下文，可为空）
 */
public record DuelRunSpec(
        String host, int port, String name, String move,
        String deckYdkText, Path deckPath,
        String archetype, boolean comboRecord,
        Path duelLogPath, Path tracePath, String deckStrategy) {

    public DuelRunSpec {
        if (host == null || host.isBlank()) host = "127.0.0.1";
        if (port <= 0) port = 7911;
        if (name == null || name.isBlank()) name = "ygo-play-runner";
        if (move == null || move.isBlank()) move = "second";
        if (comboRecord) {
            move = "second"; // combo 录入：先手让给用户
        }
    }
}
