package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;

import java.util.List;
import java.util.Optional;

public record DecisionPoint(
        long seq,
        int turn,
        Phase phase,
        PlayerSide side,
        ChoiceRequest request,
        DuelState state,
        List<Candidate> candidates,
        String stateHash,
        String requestHash,
        RequestKind kind
) {

    public DecisionPoint {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public Optional<Candidate> candidate(CandidateId id) {
        return candidates.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public <T extends Candidate> List<T> candidatesOf(Class<T> type) {
        return candidates.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
