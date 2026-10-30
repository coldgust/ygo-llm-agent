package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.map.SelectionException;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;

/**
 * 把意图还原成 ChoiceSelection（按引用回指请求候选对象）。
 * 实现必须对 19 类请求穷举；非法意图抛 {@link SelectionException}。
 */
public interface SelectionMapper {

    ChoiceSelection map(DecisionPoint point, ChoiceIntent intent);
}
