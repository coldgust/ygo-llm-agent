package com.github.coldgust.ygoagent.selection;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygoagent.support.RequestQuirks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class FallbackSelections {

    public static ChoiceSelection firstLegal(ChoiceRequest r) {
        if (r instanceof ChoiceRequest.MainPhaseRequest m) {
            if (!m.normalSummonable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Summon(m.normalSummonable().getFirst()));
            }
            if (!m.specialSummonable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SpecialSummon(m.specialSummonable().getFirst()));
            }
            if (!m.activatable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Activate(m.activatable().getFirst()));
            }
            if (!m.spellTrapSettable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetSpellTrap(m.spellTrapSettable().getFirst()));
            }
            if (!m.monsterSettable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetMonster(m.monsterSettable().getFirst()));
            }
            if (m.canEnterBattlePhase()) {
                return new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EnterBattlePhase.INSTANCE);
            }
            return new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE);
        }
        if (r instanceof ChoiceRequest.BattlePhaseRequest b) {
            if (!b.attackable().isEmpty()) {
                return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.Attack(b.attackable().getFirst()));
            }
            if (!b.activatable().isEmpty()) {
                return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.BattleActivate(b.activatable().getFirst()));
            }
            return new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EndBattlePhase.INSTANCE);
        }
        if (r instanceof ChoiceRequest.ChainRequest c) {
            for (var option : c.options()) {
                if (option.forced()) {
                    return new ChoiceSelection.ChainSelection(new ChoiceSelection.ChainAnswer.Respond(option));
                }
            }
            return new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE);
        }
        if (r instanceof ChoiceRequest.CardSelectRequest s) {
            if (s.min() == 0 && s.cancelable()) {
                return ChoiceSelection.Cancel.INSTANCE;
            }
            int take = Math.min(s.min(), s.candidates().size());
            return new ChoiceSelection.CardSelection(new ArrayList<>(s.candidates().subList(0, take)));
        }
        if (r instanceof ChoiceRequest.TributeSelectRequest t) {
            int take = Math.min(t.min(), t.candidates().size());
            return new ChoiceSelection.TributeSelection(new ArrayList<>(t.candidates().subList(0, take)));
        }
        if (r instanceof ChoiceRequest.SumRequest s) {
            List<ChoiceRequest.CardChoice> picked = new ArrayList<>(s.mustSelect());
            int need = Math.max(s.min() - picked.size(), 0);
            picked.addAll(s.optional().subList(0, Math.min(need, s.optional().size())));
            return new ChoiceSelection.SumSelection(picked);
        }
        if (r instanceof ChoiceRequest.UnselectRequest u) {
            if (u.finishable() && u.selected().size() >= u.min()) {
                return new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
            }
            for (var card : u.selectable()) {
                boolean already = u.selected().stream().anyMatch(c -> c == card);
                if (!already) {
                    return new ChoiceSelection.UnselectSelection(new ChoiceSelection.UnselectStep.Select(card));
                }
            }
            return new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
        }
        if (r instanceof ChoiceRequest.PlaceRequest p) {
            int take = RequestQuirks.placeZoneCount(p.count(), p.availableZones().size());
            return new ChoiceSelection.PlaceSelection(new ArrayList<>(p.availableZones().subList(0, take)));
        }
        if (r instanceof ChoiceRequest.DisfieldRequest d) {
            int take = RequestQuirks.placeZoneCount(d.count(), d.availableZones().size());
            return new ChoiceSelection.PlaceSelection(new ArrayList<>(d.availableZones().subList(0, take)));
        }
        if (r instanceof ChoiceRequest.PositionRequest p) {
            Position position = p.positions().contains(Position.FACE_UP_DEFENSE)
                    ? Position.FACE_UP_DEFENSE
                    : p.positions().stream().findFirst().orElse(Position.FACE_UP_ATTACK);
            return new ChoiceSelection.PositionSelection(position);
        }
        if (r instanceof ChoiceRequest.OptionRequest) {
            return new ChoiceSelection.OptionSelection(0);
        }
        if (r instanceof ChoiceRequest.EffectYesNoRequest) {
            return new ChoiceSelection.EffectYesNoSelection(true);
        }
        if (r instanceof ChoiceRequest.YesNoRequest) {
            return new ChoiceSelection.YesNoSelection(true);
        }
        if (r instanceof ChoiceRequest.OptionRequest) {
            return new ChoiceSelection.OptionSelection(0);
        }
        if (r instanceof ChoiceRequest.DeclareRaceRequest d) {
            return new ChoiceSelection.DeclareRaceSelection(
                    d.available().stream().limit(d.count()).collect(Collectors.toSet()));
        }
        if (r instanceof ChoiceRequest.DeclareAttributeRequest d) {
            return new ChoiceSelection.DeclareAttributeSelection(
                    d.available().stream().limit(d.count()).collect(Collectors.toSet()));
        }
        if (r instanceof ChoiceRequest.DeclareCardRequest) {
            return new ChoiceSelection.DeclareCardSelection(RequestQuirks.DEFAULT_DECLARE_CARD);
        }
        if (r instanceof ChoiceRequest.DeclareNumberRequest) {
            return new ChoiceSelection.DeclareNumberSelection(0);
        }
        if (r instanceof ChoiceRequest.SortRequest s) {
            return new ChoiceSelection.SortSelection(new ArrayList<>(s.cards()));
        }
        if (r instanceof ChoiceRequest.CounterRequest c) {
            Map<ChoiceRequest.CardChoice, Integer> removals = new LinkedHashMap<>();
            int remaining = c.total();
            for (var candidate : c.candidates()) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, Math.max(candidate.available(), 0));
                if (take > 0) {
                    removals.put(candidate.card(), take);
                    remaining -= take;
                }
            }
            return new ChoiceSelection.CounterSelection(removals);
        }
        throw new SelectionException("无法保底: " + r.getClass().getSimpleName());
    }

    private FallbackSelections() {
    }
}
