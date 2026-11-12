package com.github.coldgust.ygoagent.pipeline.reflex;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.support.TestFixtures;

/**
 * REFLEX 是"零策略假设层"：只有答案唯一时才应答，有取舍的一律让行。
 */
class ReflexPolicyTest {

    private final ReflexPolicyImpl reflex = new ReflexPolicyImpl();

    private static ChoiceRequest.ChainRequest.ChainOption option(ChoiceRequest.EffectChoice effect,
                                                                 boolean forced) {
        return new ChoiceRequest.ChainRequest.ChainOption(effect, forced);
    }

    @Test
    void chainDeclinesOnlyWhenThereIsNothingToRespond() {
        var point = TestFixtures.point(new ChoiceRequest.ChainRequest(List.of()),
                TestFixtures.state(List.of(), List.of(), 8000, 8000));
        assertInstanceOf(ChoiceIntent.ChainDecline.class, reflex.tryAnswer(point).orElseThrow(),
                "无候选时弃权是唯一合法动作");
    }

    @Test
    void chainWithOptionalEffectIsLeftToPlanner() {
        // 有 1 个可选的灰流丽：值不值得丢，是规划器/直答 的判断
        var card = TestFixtures.handCard(1, 14558127);
        var effect = new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0);
        var point = TestFixtures.point(new ChoiceRequest.ChainRequest(List.of(option(effect, false))),
                TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(point).isEmpty(),
                "可选的连锁响应绝不能由 REFLEX 直接弃权");
    }

    @Test
    void chainWithMultipleOptionsIsLeftToPlannerEvenIfForced() {
        var c1 = TestFixtures.handCard(1, 14558127);
        var c2 = TestFixtures.handCard(2, 10045474);
        var e1 = new ChoiceRequest.EffectChoice(TestFixtures.choice(c1), 0);
        var e2 = new ChoiceRequest.EffectChoice(TestFixtures.choice(c2), 0);
        var point = TestFixtures.point(
                new ChoiceRequest.ChainRequest(List.of(option(e1, true), option(e2, false))),
                TestFixtures.state(List.of(c1, c2), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(point).isEmpty(),
                "含必发但可再叠加自己的效果（自排/资源）→ 让行");
    }

    @Test
    void chainAnswersOnlyTheSingleForcedOption() {
        var card = TestFixtures.handCard(1, 14558127);
        var effect = new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0);
        var point = TestFixtures.point(new ChoiceRequest.ChainRequest(List.of(option(effect, true))),
                TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        assertInstanceOf(ChoiceIntent.ChainRespond.class, reflex.tryAnswer(point).orElseThrow());
    }

    @Test
    void effectYesNoIsLeftToPlanner() {
        var card = TestFixtures.handCard(1, 14558127);
        var point = TestFixtures.point(new ChoiceRequest.EffectYesNoRequest(TestFixtures.choice(card), 0),
                TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(point).isEmpty(), "是否发动某效果是资源决策");
    }

    @Test
    void positionIsAnsweredOnlyWhenSingleChoice() {
        var card = TestFixtures.handCard(1, 123);
        var unique = TestFixtures.point(
                new ChoiceRequest.PositionRequest(TestFixtures.choice(card), Set.of(Position.FACE_DOWN_DEFENSE)),
                TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        var intent = (ChoiceIntent.PositionChoice) reflex.tryAnswer(unique).orElseThrow();
        assertEquals(Position.FACE_DOWN_DEFENSE, intent.position());

        var either = TestFixtures.point(new ChoiceRequest.PositionRequest(TestFixtures.choice(card),
                        Set.of(Position.FACE_UP_ATTACK, Position.FACE_UP_DEFENSE)),
                TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(either).isEmpty(), "攻/守影响战损，属策略");
    }

    @Test
    void placeTakesFirstZonesDeterministically() {
        // 位置由模型在"选动作"时一并给出（计划 zone / 直答 zone），执行器先兑现它；
        // 这里是不兑现时的兜底：取第一个可放置区域，不为位置单独发一次 LLM 请求。
        ZoneRef z1 = ZoneRef.mainMonster(PlayerSide.SELF, 0);
        ZoneRef z2 = ZoneRef.mainMonster(PlayerSide.SELF, 1);
        var request = new ChoiceRequest.PlaceRequest(1, List.of(z1, z2));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var place = (ChoiceIntent.Place) reflex.tryAnswer(point).orElseThrow();
        assertEquals(List.of("C1"), place.zones().stream().map(id -> id.value()).toList(),
                "兜底策略：取第一个可放置位置");
    }

    @Test
    void placeWithSingleZoneIsDeterministic() {
        ZoneRef only = ZoneRef.mainMonster(PlayerSide.SELF, 2);
        var request = new ChoiceRequest.PlaceRequest(1, List.of(only));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var place = (ChoiceIntent.Place) reflex.tryAnswer(point).orElseThrow();
        assertEquals(List.of("C1"), place.zones().stream()
                .map(id -> id.value()).toList(), "只有一个格子时不需要问 LLM");
    }

    @Test
    void placeTakesAllWhenEveryZoneIsRequired() {
        ZoneRef z1 = ZoneRef.mainMonster(PlayerSide.SELF, 0);
        ZoneRef z2 = ZoneRef.mainMonster(PlayerSide.SELF, 1);
        var request = new ChoiceRequest.PlaceRequest(2, List.of(z1, z2));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var place = (ChoiceIntent.Place) reflex.tryAnswer(point).orElseThrow();
        assertEquals(2, place.zones().size(), "必须全放时选哪个格子没有差别");
    }

    @Test
    void selectsCardsOnlyWhenForcedToTakeAll() {
        var c1 = TestFixtures.handCard(1, 111);
        var c2 = TestFixtures.handCard(2, 222);
        var state = TestFixtures.state(List.of(c1, c2), List.of(), 8000, 8000);

        var all = TestFixtures.point(new ChoiceRequest.CardSelectRequest(2, 2, false,
                List.of(TestFixtures.choice(c1), TestFixtures.choice(c2))), state);
        var select = (ChoiceIntent.Select) reflex.tryAnswer(all).orElseThrow();
        assertEquals(2, select.cards().size());

        var pickOneOfTwo = TestFixtures.point(new ChoiceRequest.CardSelectRequest(1, 2, false,
                List.of(TestFixtures.choice(c1), TestFixtures.choice(c2))), state);
        assertTrue(reflex.tryAnswer(pickOneOfTwo).isEmpty(), "选哪张送墓/除外是策略");
    }

    @Test
    void leavesMainAndBattleToTactical() {
        var card = TestFixtures.handCard(1, 123);
        var main = new ChoiceRequest.MainPhaseRequest(List.of(TestFixtures.choice(card)), List.of(),
                List.of(), List.of(), List.of(), List.of(), true, true, false);
        var mainPoint = TestFixtures.point(main, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(mainPoint).isEmpty());

        var battle = new ChoiceRequest.BattlePhaseRequest(List.of(), List.of(), true, true);
        var battlePoint = TestFixtures.point(battle, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        assertTrue(reflex.tryAnswer(battlePoint).isEmpty());
    }

    @Test
    void keepsAnsweringRequestsWithoutReadableText() {
        // 只有描述码/opcodes 的请求：让行也不能让模型判断得更好，保留确定性
        var card = TestFixtures.handCard(1, 123);
        List<ChoiceRequest> requests = List.of(
                new ChoiceRequest.YesNoRequest(0),
                new ChoiceRequest.OptionRequest(List.of(0L, 1L)),
                new ChoiceRequest.DeclareNumberRequest(List.of(1, 2)),
                new ChoiceRequest.DeclareRaceRequest(1, Set.of()),
                new ChoiceRequest.CounterRequest(1, 1,
                        List.of(new ChoiceRequest.CounterRequest.CounterChoice(TestFixtures.choice(card), 1)))
        );
        for (ChoiceRequest request : requests) {
            var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
            Optional<ChoiceIntent> intent = reflex.tryAnswer(point);
            assertTrue(intent.isPresent(), "should answer " + request.getClass().getSimpleName());
        }
    }

    @Test
    void leavesSortToPlanner() {
        var card = TestFixtures.handCard(1, 123);
        var other = TestFixtures.handCard(2, 456);
        var state = TestFixtures.state(List.of(card, other), List.of(), 8000, 8000);
        assertTrue(reflex.tryAnswer(TestFixtures.point(
                new ChoiceRequest.SortRequest(List.of(TestFixtures.choice(card),
                        TestFixtures.choice(other))), state)).isEmpty(), "排序影响后续抽卡，是策略");
    }
}
