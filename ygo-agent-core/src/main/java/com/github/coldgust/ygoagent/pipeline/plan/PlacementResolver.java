package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.TurnMemory;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Optional;

/**
 * ⓪ 位置兑现（在 ① REFLEX 之前）：把模型在<b>选动作时一并给出</b>的位置落到这次 PLACE 请求上。
 *
 * <p>优先级：直答/计划接地时模型当场给的 zone（最新）→ 计划当前步的 zone（早先规划）→ 空。
 * 空则落回 ① REFLEX 的"取第一个可放置区域"——<b>不额外发一次请求去问位置</b>。
 * 模型给的格子不在本次可放置范围内（被占/规则不允许）时同样视为没给。
 */
public final class PlacementResolver {

    private final PlannerSession planner;
    private final TurnMemory memory;

    public PlacementResolver(PlannerSession planner, TurnMemory memory) {
        this.planner = planner;
        this.memory = memory;
    }

    /** 尝试用位置线索应答本次 PLACE 请求；接不上返回空（继续走 ①）。 */
    public Optional<DuelDecision> resolve(DecisionPoint point) {
        List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        Optional<ZoneRef> hint = memory.consumeZoneHint(point.turn());
        if (hint.isPresent()) {
            Optional<Candidate.ZoneC> hit = zones.stream()
                    .filter(z -> hint.get().equals(z.value()))
                    .findFirst();
            if (hit.isPresent()) {
                return Optional.of(new DuelDecision(
                        new ChoiceIntent.Place(List.of(hit.get().id())), PathKind.PLAN_STEP, 0.8,
                        DecisionExplanation.of("模型给出的位置：" + ZoneCodes.describe(hint.get())),
                        Timing.ZERO));
            }
        }
        return planPlacement(point, zones);
    }

    /**
     * 计划里的位置：写在**动作那一步**（{@code summon 零衣 zone=MMZ-3}），
     * 引擎却把"放哪格"拆成紧随其后的独立请求 → 回看刚执行过的一两步。
     * 只回看两步：再往前就是别的动作了，套错格子比不套更糟。
     */
    private Optional<DuelDecision> planPlacement(DecisionPoint point, List<Candidate.ZoneC> zones) {
        TurnPlan plan = planner.currentPlan().filter(p -> p.turnNumber() == point.turn()).orElse(null);
        if (plan == null || plan.steps().isEmpty()) {
            return Optional.empty();
        }
        int lowest = Math.max(0, plan.cursor() - 2);
        for (int i = Math.min(plan.cursor(), plan.steps().size()) - 1; i >= lowest; i--) {
            PlanStep step = plan.steps().get(i);
            if (step.alternatives().isEmpty()) {
                continue;
            }
            ZoneRef zone = step.alternatives().getFirst().zone();
            if (zone == null) {
                continue;
            }
            Optional<Candidate.ZoneC> hit = zones.stream()
                    .filter(z -> zone.equals(z.value()))
                    .findFirst();
            if (hit.isEmpty()) {
                continue; // 计划要的格子不可放：不猜，交给第一个可放置
            }
            return Optional.of(new DuelDecision(
                    new ChoiceIntent.Place(List.of(hit.get().id())), PathKind.PLAN_STEP,
                    Math.max(0.6, step.confidence()),
                    DecisionExplanation.of("计划位置：第 " + (i + 1) + " 步指定 "
                            + ZoneCodes.describe(zone)), Timing.ZERO));
        }
        return Optional.empty();
    }
}
