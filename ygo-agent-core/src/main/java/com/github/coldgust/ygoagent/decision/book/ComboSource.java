package com.github.coldgust.ygoagent.decision.book;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;

import java.util.List;
import java.util.Optional;

/**
 * 连招热索引 SPI：给定决策点，返回"此刻应该优先执行的首步"。
 * <p>实现由宿主（ygo-play 的 {@code LibraryComboSource}）注入，Agent 不依赖连招库本体。
 * 语义动作不含协议下标。
 */
public interface ComboSource {

    Optional<ComboHint> match(DecisionPoint point);

    static ComboSource noop() {
        return point -> Optional.empty();
    }

    /**
     * 命中的连招建议。
     *
     * @param comboId    连招 id
     * @param next       当前决策点优先执行的首步
     * @param steps      完整步骤（后续决策点可继续使用）
     * @param payoff     收益描述
     * @param confidence 置信度
     */
    record ComboHint(String comboId, ComboStep next, List<ComboStep> steps,
                     String payoff, double confidence) {

        public ComboHint {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * 语义动作。
     *
     * @param verb     activate / summon / specialSummon / setMonster / setSpellTrap / attack / link
     * @param passcode 主体卡号
     */
    record ComboStep(String verb, long passcode) {
    }
}
