package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.TurnMemory;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Optional;

/**
 * ⓪ 位置兑现（在 ① 唯一合法之前）：把决策器在<b>选动作时一并给出</b>的位置落到这次 PLACE 请求上。
 *
 * <p>线索来源只有一处：直答/抢答时代模型当场给的 zone（{@code TurnMemory} 位置线索，最新）。
 * 空则落回 ① 的"取第一个可放置区域"——<b>不额外发一次请求去问位置</b>。
 * 模型给的格子不在本次可放置范围内（被占/规则不允许）时同样视为没给。
 * （docs/planner-judge-memory-redesign.md §8.1：计划步回看随路径规划删除。）
 */
public final class PlacementResolver {

    private final TurnMemory memory;

    public PlacementResolver(TurnMemory memory) {
        this.memory = memory;
    }

    /** 尝试用位置线索应答本次 PLACE 请求；接不上返回空（继续走 ①）。 */
    public Optional<DuelDecision> resolve(DecisionPoint point) {
        List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        Optional<ZoneRef> hint = memory.consumeZoneHint(point.turn());
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        return zones.stream()
                .filter(z -> hint.get().equals(z.value()))
                .findFirst()
                .map(hit -> new DuelDecision(
                        new ChoiceIntent.Place(List.of(hit.id())), PathKind.ZONE_HINT, 0.8,
                        DecisionExplanation.of("决策器给出的位置：" + ZoneCodes.describe(hint.get())),
                        Timing.ZERO));
    }
}
