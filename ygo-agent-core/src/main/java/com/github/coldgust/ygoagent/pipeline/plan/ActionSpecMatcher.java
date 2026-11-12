package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneRef;import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.DecisionPoint;

import java.util.List;

/**
 * 计划接地（v6 执行器 StepGrounder 的语义基座）：计划步骤必须命中当前决策点的真实候选。
 * <p>确定性、无 IO；执行层绝不"造"候选。
 */
public final class ActionSpecMatcher {

    public static boolean matchSpec(DecisionPoint point, Candidate candidate, ActionSpec spec) {
        if (spec.passcode() > 0 && passcode(candidate) != spec.passcode()) {
            return false;
        }
        if (spec.zone() != null && !zoneCompatible(spec.zone(), candidate)) {
            return false;
        }
        String verb = spec.verb();
        return switch (candidate) {
            case Candidate.EffectC ignored -> verb.equals("activate");
            case Candidate.AttackC ignored -> verb.equals("attack");
            case Candidate.ZoneC ignored -> verb.equals("place");
            case Candidate.CounterC ignored -> verb.equals("counter");
            case Candidate.CardC card -> matchesCard(point, card, verb);
            default -> false;
        };
    }

    static boolean zoneCompatible(ZoneRef planned, Candidate candidate) {
        ZoneRef actual = zone(candidate);
        if (actual == null) {
            return false;
        }
        if (candidate instanceof Candidate.ZoneC) {
            return planned.equals(actual);
        }
        if (!ZoneCodes.isBoardSlot(actual)) {
            return true; // 源区在手牌/卡组/额外卡组 → planned 是目的地
        }
        return planned.equals(actual);
    }

    private static boolean matchesCard(DecisionPoint point, Candidate.CardC card, String verb) {
        if (verb.equals("select") || verb.equals("any")) {
            return true;
        }
        if (!(point.request() instanceof ChoiceRequest.MainPhaseRequest main)) {
            return false;
        }
        return switch (verb) {
            case "summon" -> contains(main.normalSummonable(), card.value());
            case "specialsummon", "link" -> contains(main.specialSummonable(), card.value());
            case "setmonster" -> contains(main.monsterSettable(), card.value());
            case "setspelltrap" -> contains(main.spellTrapSettable(), card.value());
            case "reposition" -> contains(main.repositionable(), card.value());
            default -> false;
        };
    }

    private static boolean contains(List<ChoiceRequest.CardChoice> group, ChoiceRequest.CardChoice target) {
        return group.stream().anyMatch(c -> c == target);
    }

    public static long passcode(Candidate candidate) {
        return switch (candidate) {
            case Candidate.CardC c -> c.value().identity().passcode();
            case Candidate.EffectC c -> c.value().card().identity().passcode();
            case Candidate.AttackC c -> c.value().card().identity().passcode();
            case Candidate.TributeC c -> c.value().card().identity().passcode();
            case Candidate.CounterC c -> c.value().card().identity().passcode();
            case Candidate.ZoneC ignored -> 0L;
            case Candidate.FlagC ignored -> 0L;
            case Candidate.DeclineC ignored -> 0L;
        };
    }

    public static ZoneRef zone(Candidate candidate) {
        return switch (candidate) {
            case Candidate.CardC c -> c.value().zone();
            case Candidate.EffectC c -> c.value().card().zone();
            case Candidate.AttackC c -> c.value().card().zone();
            case Candidate.TributeC c -> c.value().card().zone();
            case Candidate.CounterC c -> c.value().card().zone();
            case Candidate.ZoneC c -> c.value();
            case Candidate.FlagC ignored -> null;
            case Candidate.DeclineC ignored -> null;
        };
    }

    public ActionSpecMatcher() {
    }
}
