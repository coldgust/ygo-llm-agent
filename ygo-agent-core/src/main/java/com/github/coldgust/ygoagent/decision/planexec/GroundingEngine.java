package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.plan.ActionSpec;
import com.github.coldgust.ygoagent.decision.plan.CandidateIntents;
import com.github.coldgust.ygoagent.decision.plan.PlanGrounder;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.plan.PlanStep;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计划步 → 候选的接地引擎（v6 §2.2.3）。
 * <p>确定性、无 IO；绝不"造"候选。支持卡类动词（复用 {@link PlanGrounder} 的匹配语义）
 * 与标志动词（end_turn/enter_battle 等无候选动作）。
 */
public final class GroundingEngine {

    public enum Status { GROUNDED, FLAG, NOT_GROUNDED, AMBIGUOUS }

    public record GroundingResult(Status status, CandidateId candidate, String evidence) {
        public static GroundingResult notGrounded(String reason) {
            return new GroundingResult(Status.NOT_GROUNDED, null, reason);
        }
    }

    public Optional<ChoiceIntent> toIntent(DecisionPoint point, PlanStep step) {
        GroundingResult result = ground(point, step);
        if (result.status() == Status.NOT_GROUNDED || result.status() == Status.AMBIGUOUS) {
            return Optional.empty();
        }
        if (result.status() == Status.FLAG) {
            return flagIntent(point, step.alternatives().isEmpty() ? "" : step.alternatives().getFirst().verb());
        }
        CandidateId id = result.candidate();
        return point.candidate(id).flatMap(c -> CandidateIntents.toIntent(point, c));
    }

    /**
     * 接地：遍历候选，收集全部匹配；同 passcode 的多命中取第一个（同卡多实例，选择无差异）；
     * 不同 passcode 多命中 → AMBIGUOUS（不猜）。
     */
    public GroundingResult ground(DecisionPoint point, PlanStep step) {
        if (step.alternatives().isEmpty()) {
            return GroundingResult.notGrounded("no-action-spec");
        }
        ActionSpec spec = step.alternatives().getFirst();
        String verb = spec.verb();
        if (isFlagVerb(verb)) {
            return flagAllowed(point, verb)
                    ? new GroundingResult(Status.FLAG, null, verb)
                    : GroundingResult.notGrounded("flag-not-allowed:" + verb);
        }
        List<Candidate> matched = new ArrayList<>();
        List<Long> passcodes = new ArrayList<>();
        boolean handOnly = false;
        for (Candidate candidate : point.candidates()) {
            if (PlanGrounder.matchSpec(point, candidate, spec)) {
                if (point.kind() == RequestKind.CHAIN && isFromHand(candidate)) {
                    handOnly = true;
                    continue;
                }
                matched.add(candidate);
                long passcode = PlanGrounder.passcode(candidate);
                if (!passcodes.contains(passcode)) {
                    passcodes.add(passcode);
                }
            }
        }
        if (matched.isEmpty()) {
            return GroundingResult.notGrounded(handOnly
                    ? "hand-timing:" + verb + "（手牌响应交给决策器判断）"
                    : "no-candidate-matches:" + verb + ":" + spec.passcode());
        }
        if (passcodes.size() > 1) {
            return new GroundingResult(Status.AMBIGUOUS, null,
                    "multi-passcode:" + passcodes);
        }
        return new GroundingResult(Status.GROUNDED, matched.getFirst().id(),
                verb + ":" + spec.passcode() + (matched.size() > 1 ? "(same-name×" + matched.size() + ")" : ""));
    }

    /** 候选是否来自手牌（手牌里的连锁响应时机由对手决定，不按计划接地）。 */
    private static boolean isFromHand(Candidate candidate) {
        ZoneRef zone = PlanGrounder.zone(candidate);
        return zone != null && zone.kind() == ZoneKind.HAND;
    }

    private static boolean isFlagVerb(String verb) {
        return switch (verb) {
            case "end_turn", "enter_battle", "enter_main2", "end_battle" -> true;
            default -> false;
        };
    }

    private static boolean flagAllowed(DecisionPoint point, String verb) {
        return switch (verb) {
            case "end_turn" -> point.request() instanceof ChoiceRequest.MainPhaseRequest m && m.canEndTurn();
            case "enter_battle" -> point.request() instanceof ChoiceRequest.MainPhaseRequest m && m.canEnterBattlePhase();
            case "enter_main2" -> point.request() instanceof ChoiceRequest.BattlePhaseRequest b && b.canEnterMainPhase2();
            case "end_battle" -> point.request() instanceof ChoiceRequest.BattlePhaseRequest b && b.canEndBattlePhase();
            default -> false;
        };
    }

    private Optional<ChoiceIntent> flagIntent(DecisionPoint point, String verb) {
        return switch (verb) {
            case "end_turn" -> Optional.of(new ChoiceIntent.EndTurn());
            case "enter_battle" -> Optional.of(new ChoiceIntent.EnterBattlePhase());
            case "enter_main2" -> Optional.of(new ChoiceIntent.EnterMainPhase2());
            case "end_battle" -> Optional.of(new ChoiceIntent.EndBattlePhase());
            default -> Optional.empty();
        };
    }

    public GroundingEngine() {
    }
}
