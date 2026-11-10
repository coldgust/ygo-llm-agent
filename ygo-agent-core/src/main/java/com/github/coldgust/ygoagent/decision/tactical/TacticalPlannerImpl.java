package com.github.coldgust.ygoagent.decision.tactical;

import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoagent.decision.book.ComboSource;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.EvidencePack;
import com.github.coldgust.ygoagent.decision.model.Line;
import com.github.coldgust.ygoagent.decision.model.RankedLines;
import com.github.coldgust.ygoagent.decision.port.TacticalPlanner;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class TacticalPlannerImpl implements TacticalPlanner {

    private static final int MAX_LINES = 6;

    private final ComboSource comboSource;

    public TacticalPlannerImpl() {
        this(ComboSource.noop());
    }

    public TacticalPlannerImpl(ComboSource comboSource) {
        this.comboSource = comboSource == null ? ComboSource.noop() : comboSource;
    }

    @Override
    public RankedLines plan(DecisionPoint point, EvidencePack evidence, DecisionDeadline deadline) {
        if (deadline.expired()) {
            return RankedLines.empty("deadline expired");
        }
        return switch (point.kind()) {
            case MAIN_PHASE -> mainPhase(point);
            case BATTLE_PHASE -> battlePhase(point);
            default -> RankedLines.empty("not a tactical request: " + point.kind());
        };
    }

    private RankedLines mainPhase(DecisionPoint point) {
        var request = (ChoiceRequest.MainPhaseRequest) point.request();
        DuelState state = point.state();
        boolean emptyBoard = state.monsters(PlayerSide.SELF).isEmpty();

        List<Line> lines = new ArrayList<>();
        comboLine(point).ifPresent(lines::add);
        addMain(lines, "summon", request.normalSummonable(), point, 0.62, emptyBoard ? 0.05 : 0,
                id -> new ChoiceIntent.Summon(id));
        addMain(lines, "special", request.specialSummonable(), point, 0.68, emptyBoard ? 0.05 : 0,
                id -> new ChoiceIntent.SpecialSummon(id));
        for (Candidate.EffectC effect : point.candidatesOf(Candidate.EffectC.class)) {
            for (ChoiceRequest.EffectChoice choice : request.activatable()) {
                if (choice == effect.value()) {
                    lines.add(line("activate-" + effect.id().value(),
                            new ChoiceIntent.Activate(effect.id()), 0.58, "activate"));
                }
            }
        }
        addMain(lines, "set-monster", request.monsterSettable(), point, 0.30, 0,
                id -> new ChoiceIntent.SetMonster(id));
        addMain(lines, "set-spell", request.spellTrapSettable(), point, 0.34, 0,
                id -> new ChoiceIntent.SetSpellTrap(id));
        addMain(lines, "reposition", request.repositionable(), point, 0.05, 0,
                id -> new ChoiceIntent.Reposition(id));

        if (request.canEnterBattlePhase()) {
            lines.add(line("battle", new ChoiceIntent.EnterBattlePhase(), 0.12, "可进入战斗阶段"));
        }
        if (request.canEndTurn()) {
            lines.add(line("end", new ChoiceIntent.EndTurn(), 0.10, "结束回合"));
        }
        if (lines.isEmpty()) {
            return RankedLines.empty("no main-phase action");
        }
        return rank(lines);
    }

    private Optional<Line> comboLine(DecisionPoint point) {
        if (!(point.request() instanceof ChoiceRequest.MainPhaseRequest request)) {
            return Optional.empty();
        }
        Optional<ComboSource.ComboHint> hint = comboSource.match(point);
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        ComboSource.ComboStep step = hint.get().next();
        String verb = step.verb() == null ? "" : step.verb().toLowerCase();
        for (Candidate.CardC card : point.candidatesOf(Candidate.CardC.class)) {
            long passcode = passcode(card.value());
            if (passcode != step.passcode()) {
                continue;
            }
            ChoiceIntent intent = switch (verb) {
                case "summon" -> contains(request.normalSummonable(), card.value())
                        ? new ChoiceIntent.Summon(card.id()) : null;
                case "specialsummon", "link" -> contains(request.specialSummonable(), card.value())
                        ? new ChoiceIntent.SpecialSummon(card.id()) : null;
                case "setmonster" -> contains(request.monsterSettable(), card.value())
                        ? new ChoiceIntent.SetMonster(card.id()) : null;
                case "setspelltrap" -> contains(request.spellTrapSettable(), card.value())
                        ? new ChoiceIntent.SetSpellTrap(card.id()) : null;
                default -> null;
            };
            if (intent != null) {
                return Optional.of(combo(hint.get(), intent));
            }
        }
        if ("activate".equals(verb)) {
            for (Candidate.EffectC effect : point.candidatesOf(Candidate.EffectC.class)) {
                if (passcode(effect.value().card()) == step.passcode()
                        && containsEffect(request.activatable(), effect.value())) {
                    return Optional.of(combo(hint.get(), new ChoiceIntent.Activate(effect.id())));
                }
            }
        }
        return Optional.empty();
    }

    private static Line combo(ComboSource.ComboHint hint, ChoiceIntent intent) {
        Map<String, Double> terms = new LinkedHashMap<>();
        terms.put("combo", hint.confidence());
        return new Line("COMBO:" + hint.comboId(), List.of(intent), 0.95, terms,
                "combo:" + hint.comboId() + " " + hint.payoff());
    }

    private static boolean contains(List<ChoiceRequest.CardChoice> group, ChoiceRequest.CardChoice target) {
        return group.stream().anyMatch(c -> c == target);
    }

    private static boolean containsEffect(List<ChoiceRequest.EffectChoice> group,
                                          ChoiceRequest.EffectChoice target) {
        return group.stream().anyMatch(c -> c == target);
    }

    private static long passcode(ChoiceRequest.CardChoice card) {
        return card.identity() == null ? 0 : card.identity().passcode();
    }

    private RankedLines battlePhase(DecisionPoint point) {
        var request = (ChoiceRequest.BattlePhaseRequest) point.request();
        List<Line> lines = new ArrayList<>();
        for (Candidate.AttackC attack : point.candidatesOf(Candidate.AttackC.class)) {
            double score = attack.value().directAttack() ? 0.72 : 0.62;
            String text = attack.value().directAttack() ? "直接攻击" : "攻击怪兽";
            lines.add(line("attack-" + attack.id().value(),
                    new ChoiceIntent.Attack(attack.id()), score, text));
        }
        for (Candidate.EffectC effect : point.candidatesOf(Candidate.EffectC.class)) {
            lines.add(line("battle-activate-" + effect.id().value(),
                    new ChoiceIntent.BattleActivate(effect.id()), 0.45, "战斗中发动效果"));
        }
        if (request.canEnterMainPhase2()) {
            lines.add(line("main2", new ChoiceIntent.EnterMainPhase2(), 0.03, "进入主阶段2"));
        }
        lines.add(line("end-battle", new ChoiceIntent.EndBattlePhase(), 0.02, "结束战斗阶段"));
        return rank(lines);
    }

    private void addMain(List<Line> lines, String prefix, List<ChoiceRequest.CardChoice> group,
                         DecisionPoint point, double base, double bonus,
                         Function<CandidateId,
                                 ChoiceIntent> factory) {
        for (Candidate.CardC card : point.candidatesOf(Candidate.CardC.class)) {
            for (ChoiceRequest.CardChoice choice : group) {
                if (choice == card.value()) {
                    lines.add(line(prefix + "-" + card.id().value(), factory.apply(card.id()),
                            base + bonus, prefix));
                }
            }
        }
    }

    private static Line line(String id, ChoiceIntent intent, double score, String evidence) {
        Map<String, Double> terms = new LinkedHashMap<>();
        terms.put("value", round(score));
        return new Line(id, List.of(intent), score, terms, evidence);
    }

    private static RankedLines rank(List<Line> lines) {
        List<Line> sorted = lines.stream()
                .sorted(Comparator.comparingDouble(Line::expectedValue).reversed())
                .limit(MAX_LINES)
                .toList();
        double confidence = 0.55;
        if (sorted.size() >= 2) {
            double gap = sorted.get(0).expectedValue() - sorted.get(1).expectedValue();
            confidence = Math.min(0.9, 0.55 + gap);
        } else if (sorted.size() == 1) {
            confidence = 0.75;
        }
        return new RankedLines(sorted, round(confidence), "single-step heuristic v0");
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
