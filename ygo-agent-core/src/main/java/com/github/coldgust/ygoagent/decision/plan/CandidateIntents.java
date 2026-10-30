package com.github.coldgust.ygoagent.decision.plan;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 候选 → 语义意图（执行/计划层共用；不做协议下标）。 */
public final class CandidateIntents {

    public static Optional<ChoiceIntent> toIntent(DecisionPoint point, Candidate candidate) {
        return switch (candidate) {
            case Candidate.EffectC effect -> {
                // 同一个"可发动效果"候选，在不同请求里的语义完全不同：
                // - 是否发动 → EffectYesNo(true)
                // - 连锁响应 → ChainRespond（编码成 ChainSelection.Respond；用 Activate 会被
                //   映射层判成"需要主阶段请求"而抛异常，最后被兜成弃权——实机 24 次连锁全弃权就是这么来的）
                // - 其他（主阶段/战阶）→ Activate
                if (point.request() instanceof ChoiceRequest.EffectYesNoRequest) {
                    yield Optional.of(new ChoiceIntent.EffectYesNo(true));
                }
                if (point.request() instanceof ChoiceRequest.ChainRequest) {
                    yield Optional.of(new ChoiceIntent.ChainRespond(effect.id()));
                }
                yield Optional.of(new ChoiceIntent.Activate(effect.id()));
            }
            case Candidate.AttackC attack -> Optional.of(new ChoiceIntent.Attack(attack.id()));
            case Candidate.ZoneC zone -> Optional.of(new ChoiceIntent.Place(List.of(zone.id())));
            case Candidate.CounterC counter -> Optional.of(new ChoiceIntent.Counter(
                    Map.of(counter.id(), Math.max(1, counter.value().available()))));
            case Candidate.FlagC flag -> Optional.of(switch (flag.flag()) {
                case ENTER_BATTLE -> new ChoiceIntent.EnterBattlePhase();
                case END_TURN -> new ChoiceIntent.EndTurn();
                case SHUFFLE_HAND -> new ChoiceIntent.ShuffleHand();
                case ENTER_MAIN2 -> new ChoiceIntent.EnterMainPhase2();
                case END_BATTLE -> new ChoiceIntent.EndBattlePhase();
            });
            case Candidate.DeclineC decline -> Optional.of(switch (decline.kind()) {
                case CHAIN_DECLINE -> new ChoiceIntent.ChainDecline();
                case EFFECT_NO -> new ChoiceIntent.EffectYesNo(false);
                case YES_NO_NO -> new ChoiceIntent.YesNo(false);
                case CANCEL -> new ChoiceIntent.Cancel();
                case UNSELECT_FINISH -> new ChoiceIntent.UnselectFinish();
            });
            case Candidate.CardC card -> cardIntent(point, card);
            default -> Optional.empty();
        };
    }

    private static Optional<ChoiceIntent> cardIntent(DecisionPoint point, Candidate.CardC card) {
        if (point.request() instanceof ChoiceRequest.MainPhaseRequest main) {
            if (contains(main.normalSummonable(), card.value())) {
                return Optional.of(new ChoiceIntent.Summon(card.id()));
            }
            if (contains(main.specialSummonable(), card.value())) {
                return Optional.of(new ChoiceIntent.SpecialSummon(card.id()));
            }
            if (contains(main.monsterSettable(), card.value())) {
                return Optional.of(new ChoiceIntent.SetMonster(card.id()));
            }
            if (contains(main.spellTrapSettable(), card.value())) {
                return Optional.of(new ChoiceIntent.SetSpellTrap(card.id()));
            }
            if (contains(main.repositionable(), card.value())) {
                return Optional.of(new ChoiceIntent.Reposition(card.id()));
            }
        }
        if (point.request() instanceof ChoiceRequest.CardSelectRequest) {
            return Optional.of(new ChoiceIntent.Select(List.of(card.id())));
        }
        if (point.request() instanceof ChoiceRequest.TributeSelectRequest) {
            return Optional.of(new ChoiceIntent.Tribute(List.of(card.id())));
        }
        if (point.request() instanceof ChoiceRequest.SumRequest) {
            return Optional.of(new ChoiceIntent.Sum(List.of(card.id())));
        }
        if (point.request() instanceof ChoiceRequest.UnselectRequest) {
            return Optional.of(new ChoiceIntent.UnselectSelect(card.id()));
        }
        return Optional.empty();
    }

    private static boolean contains(List<ChoiceRequest.CardChoice> group, ChoiceRequest.CardChoice target) {
        return group.stream().anyMatch(c -> c == target);
    }

    private CandidateIntents() {
    }
}
