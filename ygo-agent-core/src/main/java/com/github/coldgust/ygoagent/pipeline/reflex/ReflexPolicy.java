package com.github.coldgust.ygoagent.pipeline.reflex;

import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;

import java.util.Optional;

/** 表驱动快路径：命中即答；未命中（主阶段/战阶等）交给 Tactical。 */
public interface ReflexPolicy {

    Optional<ChoiceIntent> tryAnswer(DecisionPoint point);
}
