package com.github.coldgust.ygoagent.decision.planexec.prompt;

import java.util.List;

/**
 * 一次决策时，"模型应当记得的事情"——把它之前的动作与判断带进上下文。
 *
 * <p>为什么必须显式带：one-shot 每手都是一次<b>独立单轮调用</b>（system + 当前局面），
 * 没有对话历史。模型因此看不到自己刚才做过什么，也看不到上一手说过的理由，
 * 于是频繁出现"后面一手推翻前面一手"：判了"保留零露作阻抗"，几十秒后又把它解放掉。
 *
 * @param effectScope 正在结算的那个效果（"零露 ②：…那之后，可以把场上1张卡破坏"）
 * @param turnActions 本回合已提交的动作（按发生顺序，已折叠连续重复）
 * @param lastIntent  最近一手自己写的判断摘要
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
