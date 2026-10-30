package com.github.coldgust.ygoagent.decision.plan;

import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneRef;import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;

import java.util.List;

/**
 * 计划接地（v6 执行器 GroundingEngine 的语义基座）：计划步骤必须命中当前决策点的真实候选。
 * <p>确定性、无 IO；执行层绝不"造"候选。
 */
public final class PlanGrounder {

    /** 单候选 × 单动作规格的匹配（v6 执行器 GroundingEngine 复用同一语义）。 */
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

    /**
     * 计划里的 {@code zone} 有两种语义，不能混：
     * <ul>
     *   <li><b>目的地</b>：召唤/盖放/连接召唤/放置 —— 卡此刻还在手牌或额外卡组，
     *       计划说的是"要放到 MMZ-3"。拿它去比候选的 HAND 区永远不相等，
     *       结果是"召唤到 MMZ-3"这一步永远接不上地、整份计划被作废。</li>
     *   <li><b>所在地</b>：发动场上效果/攻击 —— 计划说的是"就是 STZ-2 的这张"，必须精确对上。</li>
     * </ul>
     * 区分方式：候选本身在场上（有格子的区域）才比对，否则视为目的地不做比对。
     */
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

    public PlanGrounder() {
    }
}
