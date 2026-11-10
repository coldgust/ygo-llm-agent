package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;

public interface SessionLink {

    ChoiceRequest poll();

    void respond(ChoiceSelection selection) throws Exception;

    DuelState state();

    boolean finished();

    boolean closed();
}
