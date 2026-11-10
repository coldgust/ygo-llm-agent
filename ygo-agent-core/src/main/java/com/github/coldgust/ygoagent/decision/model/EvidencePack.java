package com.github.coldgust.ygoagent.decision.model;

import java.util.List;

public record EvidencePack(List<Long> knownPasscodes, List<String> notes) {

    public EvidencePack {
        knownPasscodes = knownPasscodes == null ? List.of() : List.copyOf(knownPasscodes);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static EvidencePack empty() {
        return new EvidencePack(List.of(), List.of());
    }
}
