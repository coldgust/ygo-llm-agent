package com.github.coldgust.ygoagent.context;

import java.util.List;

/**
 * 一次决策时，"模型应当记得的事情"——把它之前的动作与判断带进上下文。
 */
public record DecisionMemory(String effectScope, List<String> turnActions, String lastIntent) {

    public static final DecisionMemory NONE = new DecisionMemory("", List.of(), "");

    public DecisionMemory {
        effectScope = effectScope == null ? "" : effectScope.trim();
        turnActions = turnActions == null ? List.of() : List.copyOf(turnActions);
        lastIntent = lastIntent == null ? "" : lastIntent.trim();
    }

    public static DecisionMemory of(String effectScope, List<String> turnActions, String lastIntent) {
        return new DecisionMemory(effectScope, turnActions, lastIntent);
    }

    public boolean isEmpty() {
        return effectScope.isEmpty() && turnActions.isEmpty() && lastIntent.isEmpty();
    }
}
