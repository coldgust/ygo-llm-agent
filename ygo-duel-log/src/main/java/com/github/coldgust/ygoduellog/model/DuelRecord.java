package com.github.coldgust.ygoduellog.model;

import java.util.List;

public record DuelRecord(DuelMeta meta, List<DecisionEpisode> episodes, int requested, int answered,
                         int contextProvided, boolean valid) {

    public DuelRecord {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }

    public double answerRate() {
        return requested == 0 ? 0 : (double) answered / requested;
    }

    public double contextCoverage() {
        return requested == 0 ? 0 : (double) contextProvided / requested;
    }
}
