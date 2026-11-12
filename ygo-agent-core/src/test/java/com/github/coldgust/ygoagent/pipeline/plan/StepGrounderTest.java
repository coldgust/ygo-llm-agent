package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.Candidates;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.plan.ActionSpec;
import com.github.coldgust.ygoagent.pipeline.plan.PlanStep;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.pipeline.TestDuels;

class StepGrounderTest {

    private final StepGrounder engine = new StepGrounder();

    private DecisionPoint mainPhasePoint() {
        var state = TestDuels.state(2, true,
                List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001),
                        TestDuels.effectChoice(12, TestDuels.WIDOW_ANCHOR, 1002)));
    }

    @Test
    void activateStepGroundsToEffectCandidate() {
        DecisionPoint point = mainPhasePoint();
        PlanStep step = step("MAIN1", "activate", TestDuels.ENGAGE);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.GROUNDED, result.status());
        Optional<ChoiceIntent> intent = engine.toIntent(point, step);
        assertTrue(intent.isPresent());
        CandidateId expected = TestDuels.byPasscode(point, TestDuels.ENGAGE).orElseThrow().id();
        assertEquals(expected, ((ChoiceIntent.Activate) intent.get()).effect());
    }

    @Test
    void endTurnFlagGroundsWhenAllowed() {
        DecisionPoint point = mainPhasePoint();
        PlanStep step = step("EP", "end_turn", 0);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.FLAG, result.status());
        Optional<ChoiceIntent> intent = engine.toIntent(point, step);
        assertTrue(intent.isPresent());
        assertEquals(ChoiceIntent.EndTurn.class, intent.get().getClass());
    }

    @Test
    void summonStepGroundsToCardCandidate() {
        DecisionPoint point = mainPhasePoint();
        PlanStep step = step("MAIN1", "summon", TestDuels.HORNET);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.GROUNDED, result.status());
        Optional<ChoiceIntent> intent = engine.toIntent(point, step);
        assertTrue(intent.isPresent());
        assertEquals(ChoiceIntent.Summon.class, intent.get().getClass());
    }

    @Test
    void unknownPasscodeNotGrounded() {
        DecisionPoint point = mainPhasePoint();
        PlanStep step = step("MAIN1", "activate", 111222333L);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.NOT_GROUNDED, result.status());
        assertTrue(engine.toIntent(point, step).isEmpty());
    }

    @Test
    void selectWithoutPasscodeIsAmbiguous() {
        // select 不带卡号：命中全部 CardC（不同 passcode）→ AMBIGUOUS，不猜
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var request = new ChoiceRequest.CardSelectRequest(1, 1, true,
                List.of(TestDuels.cardChoice(20, TestDuels.HORNET),
                        TestDuels.cardChoice(21, TestDuels.WIDOW_ANCHOR)));
        DecisionPoint point = new DecisionPoint(2, 2, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request),
                "h", "r", RequestKind.CARD_SELECT);
        PlanStep step = step("CARD_SELECT", "select", 0);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.AMBIGUOUS, result.status());
    }

    @Test
    void sameNameMultiInstanceGroundedToFirst() {
        // 同 passcode 多实例（同卡两张）：确定性取第一个，不算歧义
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var request = new ChoiceRequest.CardSelectRequest(1, 1, true,
                List.of(TestDuels.cardChoice(30, TestDuels.HORNET),
                        TestDuels.cardChoice(31, TestDuels.HORNET)));
        DecisionPoint point = new DecisionPoint(3, 2, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request),
                "h", "r", RequestKind.CARD_SELECT);
        PlanStep step = step("CARD_SELECT", "select", TestDuels.HORNET);
        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.GROUNDED, result.status());
        assertTrue(result.evidence().contains("same-name"));
    }

    @Test
    void attackVerbRequiresBattleRequest() {
        DecisionPoint point = mainPhasePoint(); // 主阶段请求
        PlanStep step = step("BATTLE", "attack", TestDuels.HORNET);
        // 主阶段请求里没有 AttackC 候选 → NOT_GROUNDED
        assertEquals(StepGrounder.Status.NOT_GROUNDED, engine.ground(point, step).status());
    }

    @Test
    void summonStepWithDestinationZoneStillGrounds() {
        // 计划写"召唤到大黄蜂 MMZ-3"：卡此刻在手牌，zone 是**目的地**，不是候选所在区。
        // 拿目的地去比手牌区会让这一步永远接不上地（进而整份计划被作废）。
        DecisionPoint point = mainPhasePoint();
        PlanStep step = new PlanStep(0, RequestKind.MAIN_PHASE,
                List.of(new ActionSpec("summon", TestDuels.HORNET,
                        ZoneRef.mainMonster(PlayerSide.SELF, 2))),
                Map.of(), Map.of("why", "抢纵列"), 0.8);
        assertEquals(StepGrounder.Status.GROUNDED, engine.ground(point, step).status());
        assertEquals(ChoiceIntent.Summon.class, engine.toIntent(point, step).orElseThrow().getClass());
    }

    @Test
    void placeStepGroundsToTheRequestedZone() {
        // 位置决策点：计划里的 zone 命中哪个格子，就提交哪个格子
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ZoneRef z1 = ZoneRef.mainMonster(PlayerSide.SELF, 0);
        ZoneRef z2 = ZoneRef.mainMonster(PlayerSide.SELF, 1);
        var request = new ChoiceRequest.PlaceRequest(1, List.of(z1, z2));
        DecisionPoint point = new DecisionPoint(4, 2, state.phase(),
                PlayerSide.SELF, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.PLACE);
        PlanStep step = new PlanStep(0, RequestKind.PLACE,
                List.of(new ActionSpec("place", 0, z2)),
                Map.of(), Map.of(), 0.8);

        assertEquals(StepGrounder.Status.GROUNDED, engine.ground(point, step).status());
        var place = (ChoiceIntent.Place) engine.toIntent(point, step).orElseThrow();
        assertEquals(1, place.zones().size());
        assertEquals("C2", place.zones().getFirst().value(), "计划要 MMZ-2，就该提交 C2 而不是第一个");
    }

    @Test
    void placeStepZoneNotInCandidatesIsNotGrounded() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var request = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 0)));
        DecisionPoint point = new DecisionPoint(5, 2, state.phase(),
                PlayerSide.SELF, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.PLACE);
        PlanStep step = new PlanStep(0, RequestKind.PLACE,
                List.of(new ActionSpec("place", 0,
                        ZoneRef.mainMonster(PlayerSide.SELF, 3))),
                Map.of(), Map.of(), 0.8);
        assertEquals(StepGrounder.Status.NOT_GROUNDED, engine.ground(point, step).status(),
                "格子不在可放置范围内就不能硬放");
    }

    @Test
    void handChainResponseIsNotGroundedByPlan() {
        // 手牌响应（G/灰/速攻）的时机由对手这一环决定，计划里的"对手特召时交G"不能在这种窗口硬发。
        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.effectChoice(80, TestDuels.HORNET, 1001); // 手牌
        var request = new ChoiceRequest.ChainRequest(List.of(
                new ChoiceRequest.ChainRequest.ChainOption(effect, false)));
        DecisionPoint point = new DecisionPoint(7, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);
        PlanStep step = new PlanStep(0, RequestKind.CHAIN,
                List.of(new ActionSpec("activate", TestDuels.HORNET, null)),
                Map.of(), Map.of("why", "对手特召时交G"), 0.8);

        StepGrounder.GroundingResult result = engine.ground(point, step);
        assertEquals(StepGrounder.Status.NOT_GROUNDED, result.status());
        assertTrue(result.evidence().startsWith("hand-timing"), result.evidence());
    }

    @Test
    void fieldChainResponseIsStillGrounded() {
        // 场上的卡（盖放/表侧魔陷）响应是可以按计划走的：不存在"时机被别人决定"的问题
        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.fieldEffectChoice(81, TestDuels.WIDOW_ANCHOR, 1001);
        var request = new ChoiceRequest.ChainRequest(List.of(
                new ChoiceRequest.ChainRequest.ChainOption(effect, false)));
        DecisionPoint point = new DecisionPoint(8, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);
        PlanStep step = new PlanStep(0, RequestKind.CHAIN,
                List.of(new ActionSpec(
                        "activate", TestDuels.WIDOW_ANCHOR, null)),
                Map.of(), Map.of("why", "对手怪兽登场后抓锚夺权"), 0.8);

        assertEquals(StepGrounder.Status.GROUNDED, engine.ground(point, step).status());
    }

    private static PlanStep step(String when, String verb, long passcode) {
        return new PlanStep(0, requestKind(verb, when),
                List.of(ActionSpec.of(verb, passcode)),
                Map.of(), Map.of("why", "test"), 0.8);
    }

    private static RequestKind requestKind(String verb, String when) {
        if (verb.equals("attack")) {
            return RequestKind.BATTLE_PHASE;
        }
        return switch (when) {
            case "BATTLE" -> RequestKind.BATTLE_PHASE;
            case "CARD_SELECT" -> RequestKind.CARD_SELECT;
            default -> RequestKind.MAIN_PHASE;
        };
    }
}
