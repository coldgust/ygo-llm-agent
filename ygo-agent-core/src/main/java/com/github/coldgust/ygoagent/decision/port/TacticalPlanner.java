package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.EvidencePack;
import com.github.coldgust.ygoagent.decision.model.RankedLines;

public interface TacticalPlanner {

    RankedLines plan(DecisionPoint point, EvidencePack evidence, DecisionDeadline deadline);
}
