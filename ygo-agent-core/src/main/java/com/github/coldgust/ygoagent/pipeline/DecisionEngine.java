package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;

public interface DecisionEngine {

    DuelDecision decide(DecisionPoint point, DecisionDeadline deadline);
}
