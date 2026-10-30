package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;

import java.util.Optional;

/** 表驱动快路径：命中即答；未命中（主阶段/战阶等）交给 Tactical。 */
public interface ReflexPolicy {

    Optional<ChoiceIntent> tryAnswer(DecisionPoint point);
}
