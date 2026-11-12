package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.RankedLines;

public interface DecisionArbiter {

    DuelDecision choose(DecisionPoint point, RankedLines ranked);
}
