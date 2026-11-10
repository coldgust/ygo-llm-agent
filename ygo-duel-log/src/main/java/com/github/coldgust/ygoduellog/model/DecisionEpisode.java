package com.github.coldgust.ygoduellog.model;

import java.util.List;

public record DecisionEpisode(
        String duelId,
        long requestSeq,
        int turn,
        String phase,
        String requestType,
        int candidateCount,
        List<CandidateView> candidates,
        String chosen,
        String source,
        long thinkMs,
        boolean answered,
        List<Long> handPasscodes,
        int selfLp,
        int oppLp,
        List<Long> fieldCards
) {

    public DecisionEpisode {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        handPasscodes = handPasscodes == null ? List.of() : List.copyOf(handPasscodes);
        fieldCards = fieldCards == null ? List.of() : List.copyOf(fieldCards);
    }
}
