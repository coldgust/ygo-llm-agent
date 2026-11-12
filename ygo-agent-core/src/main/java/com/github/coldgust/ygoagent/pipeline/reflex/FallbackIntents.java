package com.github.coldgust.ygoagent.pipeline.reflex;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.support.RequestQuirks;

import com.github.coldgust.ygodomain.duel.Position;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 最后一道保底：保证任何请求都能给出一个合法意图（优先最小代价动作）。
 * 仅在 Reflex/Tactical 异常或映射失败时使用。
 */
public final class FallbackIntents {

    public static ChoiceIntent first(DecisionPoint point) {
        ChoiceRequest r = point.request();
        if (r instanceof ChoiceRequest.MainPhaseRequest m) {
            if (!m.normalSummonable().isEmpty()) {
                return new ChoiceIntent.Summon(firstByIdentity(point, m.normalSummonable()).id());
            }
            if (!m.activatable().isEmpty()) {
                return new ChoiceIntent.Activate(effects(point).getFirst().id());
            }
            if (!m.spellTrapSettable().isEmpty()) {
                return new ChoiceIntent.SetSpellTrap(firstByIdentity(point, m.spellTrapSettable()).id());
            }
            if (!m.monsterSettable().isEmpty()) {
                return new ChoiceIntent.SetMonster(firstByIdentity(point, m.monsterSettable()).id());
            }
            if (m.canEndTurn()) {
                return new ChoiceIntent.EndTurn();
            }
            if (m.canShuffleHand()) {
                return new ChoiceIntent.ShuffleHand();
            }
            return new ChoiceIntent.EnterBattlePhase();
        }
        if (r instanceof ChoiceRequest.BattlePhaseRequest b) {
            if (!b.attackable().isEmpty()) {
                return new ChoiceIntent.Attack(attacks(point).getFirst().id());
            }
            if (!b.activatable().isEmpty()) {
                return new ChoiceIntent.BattleActivate(effects(point).getFirst().id());
            }
            return new ChoiceIntent.EndBattlePhase();
        }
        if (r instanceof ChoiceRequest.ChainRequest c) {
            if (!c.options().isEmpty() && c.hasForcedEffect()) {
                return new ChoiceIntent.ChainRespond(effects(point).getFirst().id());
            }
            return new ChoiceIntent.ChainDecline();
        }
        if (r instanceof ChoiceRequest.CardSelectRequest s) {
            List<Candidate.CardC> cards = cards(point);
            int take = Math.min(Math.max(s.min(), 0), cards.size());
            return new ChoiceIntent.Select(cards.stream().limit(take).map(Candidate.CardC::id).toList());
        }
        if (r instanceof ChoiceRequest.TributeSelectRequest t) {
            List<Candidate.TributeC> cards = point.candidatesOf(Candidate.TributeC.class);
            int take = Math.min(Math.max(t.min(), 0), cards.size());
            return new ChoiceIntent.Tribute(cards.stream().limit(take).map(Candidate.TributeC::id).toList());
        }
        if (r instanceof ChoiceRequest.SumRequest s) {
            List<Candidate.CardC> cards = cards(point);
            int take = Math.min(Math.max(s.min(), s.mustSelect().size()), cards.size());
            return new ChoiceIntent.Sum(cards.stream().limit(take).map(Candidate.CardC::id).toList());
        }
        if (r instanceof ChoiceRequest.UnselectRequest u) {
            if (u.finishable() && u.selected().size() >= u.min()) {
                return new ChoiceIntent.UnselectFinish();
            }
            List<Candidate.CardC> selectable = cards(point);
            return selectable.isEmpty()
                    ? new ChoiceIntent.UnselectFinish()
                    : new ChoiceIntent.UnselectSelect(selectable.getFirst().id());
        }
        if (r instanceof ChoiceRequest.PlaceRequest p) {
            List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
            int take = RequestQuirks.placeZoneCount(p.count(), zones.size());
            return new ChoiceIntent.Place(zones.stream().limit(take).map(Candidate.ZoneC::id).toList());
        }
        if (r instanceof ChoiceRequest.DisfieldRequest d) {
            List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
            int take = RequestQuirks.placeZoneCount(d.count(), zones.size());
            return new ChoiceIntent.Place(zones.stream().limit(take).map(Candidate.ZoneC::id).toList());
        }
        if (r instanceof ChoiceRequest.PositionRequest p) {
            var pos = p.positions().stream().findFirst().orElse(Position.FACE_UP_ATTACK);
            return new ChoiceIntent.PositionChoice(pos);
        }
        if (r instanceof ChoiceRequest.EffectYesNoRequest) {
            return new ChoiceIntent.EffectYesNo(true);
        }
        if (r instanceof ChoiceRequest.YesNoRequest) {
            return new ChoiceIntent.YesNo(true);
        }
        if (r instanceof ChoiceRequest.OptionRequest) {
            return new ChoiceIntent.Option(0);
        }
        if (r instanceof ChoiceRequest.DeclareRaceRequest d) {
            return new ChoiceIntent.DeclareRace(Set.copyOf(d.available()));
        }
        if (r instanceof ChoiceRequest.DeclareAttributeRequest d) {
            return new ChoiceIntent.DeclareAttribute(Set.copyOf(d.available()));
        }
        if (r instanceof ChoiceRequest.DeclareCardRequest) {
            return new ChoiceIntent.DeclareCard(RequestQuirks.DEFAULT_DECLARE_CARD);
        }
        if (r instanceof ChoiceRequest.DeclareNumberRequest) {
            return new ChoiceIntent.DeclareNumber(0);
        }
        if (r instanceof ChoiceRequest.SortRequest) {
            return new ChoiceIntent.Sort(point.candidatesOf(Candidate.CardC.class).stream()
                    .map(Candidate.CardC::id).toList());
        }
        if (r instanceof ChoiceRequest.CounterRequest c) {
            Map<CandidateId, Integer> removals = new LinkedHashMap<>();
            int remaining = c.total();
            for (Candidate.CounterC candidate : point.candidatesOf(Candidate.CounterC.class)) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, Math.max(candidate.value().available(), 0));
                if (take > 0) {
                    removals.put(candidate.id(), take);
                    remaining -= take;
                }
            }
            return new ChoiceIntent.Counter(removals);
        }
        throw new IllegalStateException("无法为请求生成保底意图: " + r.getClass().getSimpleName());
    }

    private static List<Candidate.CardC> cards(DecisionPoint point) {
        return point.candidatesOf(Candidate.CardC.class);
    }

    private static List<Candidate.EffectC> effects(DecisionPoint point) {
        return point.candidatesOf(Candidate.EffectC.class);
    }

    private static List<Candidate.AttackC> attacks(DecisionPoint point) {
        return point.candidatesOf(Candidate.AttackC.class);
    }

    private static Candidate.CardC firstByIdentity(DecisionPoint point,
                                                   List<ChoiceRequest.CardChoice> group) {
        for (Candidate.CardC candidate : point.candidatesOf(Candidate.CardC.class)) {
            for (ChoiceRequest.CardChoice choice : group) {
                if (choice == candidate.value()) {
                    return candidate;
                }
            }
        }
        return point.candidatesOf(Candidate.CardC.class).getFirst();
    }

    private FallbackIntents() {
    }
}
