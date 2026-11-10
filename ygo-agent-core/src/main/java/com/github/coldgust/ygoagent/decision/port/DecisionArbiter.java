package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.RankedLines;

public interface DecisionArbiter {

    DuelDecision choose(DecisionPoint point, RankedLines ranked);
}
