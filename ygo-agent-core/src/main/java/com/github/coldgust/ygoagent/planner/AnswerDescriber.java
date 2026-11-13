package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.CardLabels;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygodomain.duel.CardInstance;

import java.util.List;

/**
 * 规划器应答行的渲染（模块内部协作类型，无状态）：
 * 把已提交的意图压成一句"第 N 回合 … 应答 …"可读文本，供事件批与复盘使用。
 */
final class AnswerDescriber {

    private final CardFacts facts;

    AnswerDescriber(CardFacts facts) {
        this.facts = facts;
    }

    String describe(DecisionPoint point, ChoiceIntent intent) {
        String label = candidateLabel(point, intent);
        return switch (intent) {
            case ChoiceIntent.Summon ignored -> "通常召唤 " + label;
            case ChoiceIntent.SpecialSummon ignored -> "特殊召唤 " + label;
            case ChoiceIntent.Reposition ignored -> "改变表示形式 " + label;
            case ChoiceIntent.SetMonster ignored -> "盖放怪兽 " + label;
            case ChoiceIntent.SetSpellTrap ignored -> "盖放魔陷 " + label;
            case ChoiceIntent.Activate ignored -> "发动 " + label;
            case ChoiceIntent.BattleActivate ignored -> "战阶发动 " + label;
            case ChoiceIntent.Attack ignored -> "攻击宣言 " + label;
            case ChoiceIntent.ChainRespond ignored -> "连锁 " + label;
            case ChoiceIntent.ChainDecline ignored -> "不连锁";
            case ChoiceIntent.EnterBattlePhase ignored -> "进战阶";
            case ChoiceIntent.EndTurn ignored -> "结束回合";
            case ChoiceIntent.EnterMainPhase2 ignored -> "进 M2";
            case ChoiceIntent.EndBattlePhase ignored -> "结束战斗";
            case ChoiceIntent.Select selected -> "选卡 " + labels(point, selected.cards());
            case ChoiceIntent.Tribute t -> "祭品 " + labels(point, t.cards());
            case ChoiceIntent.Sum s -> "凑和 " + labels(point, s.cards());
            case ChoiceIntent.EffectYesNo yes -> "效果YesNo=" + yes.activate();
            case ChoiceIntent.YesNo yes -> "YesNo=" + yes.yes();
            default -> intent.getClass().getSimpleName();
        };
    }

    private String labels(DecisionPoint point, List<CandidateId> ids) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CandidateId id : ids) {
            String label = candidateLabel(point, intentOf(id));
            if (label.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append('、');
            }
            sb.append(label);
            first = false;
        }
        return sb.toString();
    }

    private ChoiceIntent intentOf(CandidateId id) {
        return new ChoiceIntent.Activate(id); // 仅借 label 通道；candidateLabel 不关心 intent 类型
    }

    private String candidateLabel(DecisionPoint point, ChoiceIntent intent) {
        CandidateId id = candidateIdOf(intent);
        if (id == null) {
            return "";
        }
        return point.candidate(id)
                .map(c -> switch (c) {
                    case Candidate.CardC card -> CardLabels.labelOf(
                            new CardInstance(
                                    card.value().instanceId(), card.value().identity(),
                                    card.value().zone(), null, null, null), facts);
                    case Candidate.EffectC effect -> CardLabels.labelOf(
                            new CardInstance(
                                    effect.value().card().instanceId(), effect.value().card().identity(),
                                    effect.value().card().zone(), null, null, null), facts)
                            + "[效果#" + effect.value().description() + "]";
                    case Candidate.AttackC attack -> CardLabels.labelOf(
                            new CardInstance(
                                    attack.value().card().instanceId(), attack.value().card().identity(),
                                    attack.value().card().zone(), null, null, null), facts);
                    case Candidate.TributeC tribute -> CardLabels.labelOf(
                            new CardInstance(
                                    tribute.value().card().instanceId(), tribute.value().card().identity(),
                                    tribute.value().card().zone(), null, null, null), facts);
                    case Candidate.ZoneC zone -> CardLabels.zoneLabel(zone.value().kind())
                            + "#" + zone.value().sequence();
                    case Candidate.CounterC ignored -> "指示物";
                    case Candidate.FlagC flag -> flag.flag().label();
                    case Candidate.DeclineC decline -> decline.kind().label();
                })
                .orElse(id.value());
    }

    private static CandidateId candidateIdOf(ChoiceIntent intent) {
        return switch (intent) {
            case ChoiceIntent.Summon i -> i.card();
            case ChoiceIntent.SpecialSummon i -> i.card();
            case ChoiceIntent.Reposition i -> i.card();
            case ChoiceIntent.SetMonster i -> i.card();
            case ChoiceIntent.SetSpellTrap i -> i.card();
            case ChoiceIntent.Activate i -> i.effect();
            case ChoiceIntent.BattleActivate i -> i.effect();
            case ChoiceIntent.Attack i -> i.attack();
            case ChoiceIntent.ChainRespond i -> i.option();
            case ChoiceIntent.Select s -> s.cards().isEmpty() ? null : s.cards().getFirst();
            case ChoiceIntent.Tribute t -> t.cards().isEmpty() ? null : t.cards().getFirst();
            case ChoiceIntent.Sum s -> s.cards().isEmpty() ? null : s.cards().getFirst();
            default -> null;
        };
    }
}
