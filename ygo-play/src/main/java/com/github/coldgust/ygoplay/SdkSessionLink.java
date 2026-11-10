package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoprosdk.session.DuelSession;

public final class SdkSessionLink implements SessionLink {

    private final DuelSession session;

    public SdkSessionLink(DuelSession session) {
        this.session = session;
    }

    @Override
    public ChoiceRequest poll() {
        return session.pollChoice();
    }

    @Override
    public void respond(ChoiceSelection selection) throws Exception {
        session.respond(selection);
    }

    @Override
    public DuelState state() {
        return session.state();
    }

    @Override
    public boolean finished() {
        DuelState state = session.state();
        return state != null && state.isFinished();
    }

    @Override
    public boolean closed() {
        return session.stage() == DuelSession.Stage.CLOSED;
    }
}
