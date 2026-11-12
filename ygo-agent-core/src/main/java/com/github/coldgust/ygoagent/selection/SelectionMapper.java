package com.github.coldgust.ygoagent.selection;

import com.github.coldgust.ygoagent.selection.SelectionException;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;

/**
 * 把意图还原成 ChoiceSelection（按引用回指请求候选对象）。
 * 实现必须对 19 类请求穷举；非法意图抛 {@link SelectionException}。
 */
public interface SelectionMapper {

    ChoiceSelection map(DecisionPoint point, ChoiceIntent intent);
}
