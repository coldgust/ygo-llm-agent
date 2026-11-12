package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.llm.CardTool;
import com.github.coldgust.ygoagent.llm.ComboTool;
import com.github.coldgust.ygoagent.llm.DuelRecallTool;
import com.github.coldgust.ygoagent.llm.RulebookTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次决策可用的工具集合（规则 / 连招 / 查卡 / 历史对局）。
 *
 * <p>把"注册哪些工具"与"端口构造参数"解耦：以后再加工具只改这里，
 * 不用把 DecisionPipeline 的构造参数继续往后堆。
 * 工具类用 langchain4j 的 @Tool 注解声明（名称/描述/参数 schema 都在注解里），
 * 本类只负责按路径挑选可用工具；spec 提取与执行由传输层完成。
 */
public record DecisionTools(RulebookTool rulebook, ComboTool combo, CardTool card,
                            DuelRecallTool duel) {

    public static final DecisionTools NONE = new DecisionTools(null, null, null, null);

    public DecisionTools(RulebookTool rulebook, ComboTool combo, CardTool card) {
        this(rulebook, combo, card, null);
    }

    public boolean isEmpty() {
        return providers().isEmpty();
    }

    /** 可注册给模型的 @Tool 工具对象（按可用性挑选）。 */
    public List<Object> providers() {
        List<Object> out = new ArrayList<>();
        if (rulebook != null) {
            out.add(rulebook);
        }
        if (combo != null && combo.available()) {
            out.add(combo);
        }
        if (card != null && card.available()) {
            out.add(card);
        }
        if (duel != null) {
            out.add(duel);
        }
        return out;
    }
}
