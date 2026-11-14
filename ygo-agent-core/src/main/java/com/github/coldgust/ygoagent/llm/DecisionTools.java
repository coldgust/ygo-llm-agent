package com.github.coldgust.ygoagent.llm;

import java.util.ArrayList;
import java.util.List;
/**
 * 一次决策可用的工具集合（规则 / 连招 / 查卡 / 历史对局 / 数学）。
 *
 * <p>把"注册哪些工具"与"端口构造参数"解耦：以后再加工具只改这里，
 * 不用把构造参数继续往后堆。
 * 工具类用 langchain4j 的 @Tool 注解声明（名称/描述/参数 schema 都在注解里），
 * 本类只负责按路径挑选可用工具；spec 提取与执行由传输层完成。
 */
public record DecisionTools(RulebookTool rulebook, ComboTool combo, CardTool card,
                            DuelRecallTool duel, MathTool math) {

    public static final DecisionTools NONE = new DecisionTools(null, null, null, null, null);

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
        if (math != null) {
            out.add(math);
        }
        return out;
    }
}
