package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.Candidates;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DecisionPointFactory {

    private final AtomicLong seq = new AtomicLong();

    public DecisionPoint create(ChoiceRequest request, DuelState state) {
        long current = seq.incrementAndGet();
        List<Candidate> candidates = Candidates.from(request);
        return new DecisionPoint(
                current,
                state.turnNumber(),
                state.phase(),
                PlayerSide.SELF,
                request,
                state,
                candidates,
                Fingerprint.stateHash(state),
                Fingerprint.requestHash(request, candidates),
                RequestKind.of(request));
    }
}
