package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.planexec.llm.CardTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoagent.decision.planexec.llm.ComboTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.DuelRecallTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.RulebookTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 一次决策可用的工具集合（规则 / 连招 / 查卡）。
 *
 * <p>把"注册哪些工具"与"端口构造参数"解耦：以后再加工具只改这里，
 * 不用把 PlanExecutorDecisionPort 的构造参数继续往后堆。
 */
public record DecisionTools(RulebookTool rulebook, ComboTool combo, CardTool card,
                            DuelRecallTool duel) {

    public static final DecisionTools NONE = new DecisionTools(null, null, null, null);

    public DecisionTools(RulebookTool rulebook, ComboTool combo, CardTool card) {
        this(rulebook, combo, card, null);
    }

    public boolean isEmpty() {
        return (rulebook == null) && (combo == null || !combo.available())
                && (card == null || !card.available()) && (duel == null);
    }

    public List<ChatClient.Tool> tools() {
        List<ChatClient.Tool> tools = new ArrayList<>();
        if (rulebook != null) {
            tools.addAll(rulebook.tools());
        }
        if (combo != null && combo.available()) {
            tools.addAll(combo.tools());
        }
        if (card != null && card.available()) {
            tools.addAll(card.tools());
        }
        if (duel != null) {
            tools.addAll(duel.tools());
        }
        return tools;
    }

    public ChatClient.ToolHandler handler(Collection<Long> visibleCards) {
        return call -> {
            if (rulebook != null && RulebookTool.NAME.equals(call.name())) {
                return rulebook.handler().execute(call);
            }
            if (combo != null && ComboTool.NAME.equals(call.name())) {
                return combo.handler(visibleCards).execute(call);
            }
            if (card != null && CardTool.NAME.equals(call.name())) {
                return card.handler().execute(call);
            }
            if (duel != null && DuelRecallTool.NAME.equals(call.name())) {
                return duel.handler().execute(call);
            }
            return "未知工具：" + call.name();
        };
    }
}
