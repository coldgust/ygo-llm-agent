package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.EvidencePack;
import com.github.coldgust.ygoagent.model.RankedLines;

public interface TacticalPlanner {

    RankedLines plan(DecisionPoint point, EvidencePack evidence, DecisionDeadline deadline);
}
