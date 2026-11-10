package com.github.coldgust.ygoduellog.query;

public record DecisionRecord(
        long requestSeq,
        long answeredSeq,
        String player,
        String requestType,
        int turn,
        String requestSummary,
        String outcome,
        String selectionSummary,
        String source,
        long thinkMs,
        int retryCount
) {
}
