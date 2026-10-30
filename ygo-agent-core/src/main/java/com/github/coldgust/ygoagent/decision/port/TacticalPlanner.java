package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.EvidencePack;
import com.github.coldgust.ygoagent.decision.model.RankedLines;

/** 战术层：候选行生成 + 静态评估。P0 为单步展开。 */
public interface TacticalPlanner {

    RankedLines plan(DecisionPoint point, EvidencePack evidence, DecisionDeadline deadline);
}
