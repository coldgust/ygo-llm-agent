package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;

public interface DecisionPort {

    DuelDecision decide(DecisionPoint point, DecisionDeadline deadline);
}
