package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.zone.ZoneKind;

/**
 * 卡名/区域的可读标签渲染（卡库无资料时保留卡号，绝不编名字）。
 * 从 {@link DuelMathService} 拆出：数学归数学，标签归标签。
 */
public final class CardLabels {

    private CardLabels() {
    }

    /** 卡的可读名（名+卡号）：未知卡保留编号而不是空串——留白等于让模型自己编。 */
    public static String labelOf(CardInstance card, CardFacts facts) {
        if (card.isUnknown()) {
            return "未知卡";
        }
        return facts.byPasscode(card.passcode()).map(CardFact::label)
                .orElseGet(() -> "卡(" + card.passcode() + ")");
    }

    /** 区域的可读名（主怪区/魔陷区/…）。 */
    public static String zoneLabel(ZoneKind kind) {
        return switch (kind) {
            case MAIN_MONSTER_ZONE -> "主怪区";
            case EXTRA_MONSTER_ZONE -> "额外怪区";
            case SPELL_TRAP_ZONE -> "魔陷区";
            case PENDULUM_ZONE -> "灵摆区";
            case FIELD_ZONE -> "场地区";
            case GRAVEYARD -> "墓地";
            case BANISHED -> "除外";
            case HAND -> "手牌";
            case DECK -> "卡组";
            case EXTRA_DECK -> "额外卡组";
        };
    }
}
