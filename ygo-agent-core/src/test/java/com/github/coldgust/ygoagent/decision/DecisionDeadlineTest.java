package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionDeadlineTest {

    @Test
    void expiresAfterBudget() throws InterruptedException {
        DecisionDeadline deadline = DecisionDeadline.of(10);
        Thread.sleep(25);
        assertTrue(deadline.expired());
        assertTrue(deadline.remainingMillis() == 0);
    }

    @Test
    void keepsDeepCutoffBeforeHardDeadline() {
        DecisionDeadline deadline = DecisionDeadline.of(1000);
        assertFalse(deadline.expired());
        assertTrue(deadline.deepCutoffNanos() < deadline.hardNanos());
    }
}
