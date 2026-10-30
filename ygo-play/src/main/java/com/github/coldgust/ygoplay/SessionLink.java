package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;

/** SDK 会话的最小抽象：仅暴露决策循环需要的操作（便于 FakeSession 测试）。 */
public interface SessionLink {

    ChoiceRequest poll();

    void respond(ChoiceSelection selection) throws Exception;

    DuelState state();

    boolean finished();

    boolean closed();
}
