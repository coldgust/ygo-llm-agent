package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 内存会话：按序给出请求并记录应答。 */
final class FakeSessionLink implements SessionLink {

    private final Deque<ChoiceRequest> queue = new ArrayDeque<>();
    private final List<ChoiceSelection> responses = new ArrayList<>();
    private final DuelState state;
    private boolean closed;

    FakeSessionLink(DuelState state, List<ChoiceRequest> requests) {
        this.state = state;
        queue.addAll(requests);
    }

    @Override
    public ChoiceRequest poll() {
        return queue.poll();
    }

    @Override
    public void respond(ChoiceSelection selection) {
        responses.add(selection);
        closed = queue.isEmpty();
    }

    @Override
    public DuelState state() {
        return state;
    }

    @Override
    public boolean finished() {
        return false;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    List<ChoiceSelection> responses() {
        return List.copyOf(responses);
    }
}
