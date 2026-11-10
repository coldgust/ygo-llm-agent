package com.github.coldgust.ygoplayserver.duel;

import java.util.Locale;

public record DuelRequest(
        String host,
        Integer port,
        String name,
        String move,
        String deckYdk,
        String deckPath,
        String archetype,
        Boolean comboRecord,
        String duelLogPath,
        String tracePath,
        String deckStrategy,
        String archivePolicy) {

    public String archivePolicyOrDefault() {
        if (archivePolicy == null) {
            return "never";
        }
        return switch (archivePolicy.trim().toLowerCase(Locale.ROOT)) {
            case "ask", "auto", "always", "never" -> archivePolicy.trim().toLowerCase(Locale.ROOT);
            default -> "never";
        };
    }
}
