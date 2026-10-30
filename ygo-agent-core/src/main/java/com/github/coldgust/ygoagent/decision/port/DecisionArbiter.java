package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.RankedLines;

/** 仲裁：P0 直接采用快路径最优行；P2 增加 Deep 结果比较。 */
public interface DecisionArbiter {

    DuelDecision choose(DecisionPoint point, RankedLines ranked);
}
