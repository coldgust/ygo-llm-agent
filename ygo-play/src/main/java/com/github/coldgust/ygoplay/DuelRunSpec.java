package com.github.coldgust.ygoplay;

import java.nio.file.Path;

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
