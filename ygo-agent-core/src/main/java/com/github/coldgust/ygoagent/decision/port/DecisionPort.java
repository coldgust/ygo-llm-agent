package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;

/** 决策入口：一个决策点 → 一个带解释的决策。 */
public interface DecisionPort {

    DuelDecision decide(DecisionPoint point, DecisionDeadline deadline);
}
