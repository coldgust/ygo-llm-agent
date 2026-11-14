package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.DecisionEngine;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.fallback.SimpleArbiter;
import com.github.coldgust.ygoagent.pipeline.fallback.RuleBasedEngine;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.pipeline.fallback.TacticalPlannerImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelSessionRunnerTest {

    @Test
    void cancelOnMandatoryUnselectIsRejectedAndAdvances() throws Exception {
        CardInstance material = monster(7, 37351133);
        DuelState state = state(List.of(material));
        var choice = choice(material);
        ChoiceRequest unselect = new ChoiceRequest.UnselectRequest(
                false, true, 1, 1, List.of(choice), List.of());

        List<ChoiceRequest> sequence = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sequence.add(unselect);
        }
        FakeSessionLink link = new FakeSessionLink(state, sequence);
        DecisionEngine alwaysCancel =
                (point, deadline) -> new DuelDecision(
                        new ChoiceIntent.Cancel(),
                        PathKind.FALLBACK, 0.5,
                        DecisionExplanation.of("取消"),
                        Timing.ZERO);

        new DuelSessionRunner(link, alwaysCancel, RunnerConfig.defaults()).run();

        for (ChoiceSelection sent : link.responses()) {
            assertInstanceOf(ChoiceSelection.UnselectSelection.class, sent,
                    "min>0 的取消必须被拒绝并改选第一张，实际: " + sent);
        }
    }

    @Test
    void alternatingLoopIsBrokenByWindowDetection() throws Exception {
        CardInstance settable = hand(8, 63166095);
        CardInstance material = monster(9, 37351133);
        DuelState state = state(List.of(settable));
        var setChoice = choice(settable);
        var materialChoice = choice(material);
        ChoiceRequest main = new ChoiceRequest.MainPhaseRequest(
                List.of(), List.of(), List.of(), List.of(), List.of(setChoice), List.of(),
                false, true, false);
        ChoiceRequest select = new ChoiceRequest.CardSelectRequest(0, 1, true, List.of(materialChoice));

        List<ChoiceRequest> sequence = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sequence.add(main);
            sequence.add(select);
        }
        FakeSessionLink link = new FakeSessionLink(state, sequence);
        DecisionEngine port = (point, deadline) -> {
            var intent = point.kind() == RequestKind.MAIN_PHASE
                    ? (ChoiceIntent)
                    new ChoiceIntent.SetSpellTrap(
                            CandidateId.of(1))
                    : new ChoiceIntent.Cancel();
            return new DuelDecision(intent,
                    PathKind.FALLBACK, 0.5,
                    DecisionExplanation.of("循环"),
                    Timing.ZERO);
        };

        new DuelSessionRunner(link, port, RunnerConfig.defaults()).run();

        List<ChoiceSelection> sent = link.responses();
        assertTrue(sent.stream().anyMatch(x -> x instanceof ChoiceSelection.MainPhaseSelection m
                        && m.action() instanceof ChoiceSelection.EndTurn),
                "交替循环必须被窗口判重熔断成结束回合: " + sent);
    }

    @Test
    void reportsWhatWasActuallySubmitted() throws Exception {
        CardInstance summoned = hand(1, 89631139);
        DuelState state = state(List.of(summoned));
        var summonChoice = choice(summoned);
        ChoiceRequest main = new ChoiceRequest.MainPhaseRequest(
                List.of(summonChoice), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, true, false);
        ChoiceRequest place = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 2)));

        FakeSessionLink link = new FakeSessionLink(state, List.of(main, place));
        RuleBasedEngine port = new RuleBasedEngine(
                new ReflexPolicyImpl(), new TacticalPlannerImpl(), new SimpleArbiter(),
                DecisionTraceWriter.NOOP);

        List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        new DuelSessionRunner(link, port, RunnerConfig.defaults(),
                submission -> summaries.add(submission.summary())).run();

        assertEquals(2, summaries.size(), "每次提交都要有一条摘要: " + summaries);
        assertTrue(summaries.get(0).startsWith("通常召唤"), summaries.get(0));
        assertEquals("放到 我方主怪兽区#3", summaries.get(1));
    }

    @Test
    void answersMixedRequestSequenceAndMapsByReference() throws Exception {
        CardInstance summoned = hand(1, 89631139);
        CardInstance second = hand(2, 6853254);
        DuelState state = state(List.of(summoned, second));

        var summonChoice = choice(summoned);
        ChoiceRequest main = new ChoiceRequest.MainPhaseRequest(
                List.of(summonChoice), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, true, false);
        ChoiceRequest place = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 2)));
        ChoiceRequest mainEnd = new ChoiceRequest.MainPhaseRequest(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, true, false);
        CardInstance attacker = monster(10, 97268402);
        ChoiceRequest battle = new ChoiceRequest.BattlePhaseRequest(List.of(),
                List.of(new ChoiceRequest.AttackChoice(choice(attacker), true)), false, true);
        var selectChoice = choice(second);
        ChoiceRequest select = new ChoiceRequest.CardSelectRequest(1, 1, false,
                List.of(selectChoice));

        FakeSessionLink link = new FakeSessionLink(state, List.of(main, place, mainEnd, battle, select));
        RuleBasedEngine port = new RuleBasedEngine(
                new ReflexPolicyImpl(), new TacticalPlannerImpl(), new SimpleArbiter(),
                DecisionTraceWriter.NOOP);

        RunStats stats = new DuelSessionRunner(link, port, RunnerConfig.defaults()).run();

        assertEquals(5, stats.answered());
        assertEquals(0, stats.fallbacks());
        assertEquals(5, link.responses().size());

        var summon = (ChoiceSelection.MainPhaseSelection) link.responses().get(0);
        var summonAction = (ChoiceSelection.Summon) summon.action();
        assertTrue(summonAction.card() == summonChoice);

        var placed = (ChoiceSelection.PlaceSelection) link.responses().get(1);
        assertEquals(ZoneRef.mainMonster(PlayerSide.SELF, 2), placed.zones().getFirst());

        var end = (ChoiceSelection.MainPhaseSelection) link.responses().get(2);
        assertInstanceOf(ChoiceSelection.EndTurn.class, end.action());

        var attack = (ChoiceSelection.BattlePhaseSelection) link.responses().get(3);
        var attackAction = (ChoiceSelection.Attack) attack.action();
        assertEquals(true, attackAction.target().directAttack());

        var cards = (ChoiceSelection.CardSelection) link.responses().get(4);
        assertEquals(1, cards.selected().size());
        assertTrue(cards.selected().getFirst() == selectChoice);
    }

    @Test
    void terminatesImmediatelyWhenClosed() throws Exception {
        DuelState state = state(List.of());
        FakeSessionLink link = new FakeSessionLink(state, List.of());
        link.respond(null); // 置为关闭
        RuleBasedEngine port = new RuleBasedEngine(
                new ReflexPolicyImpl(), new TacticalPlannerImpl(), new SimpleArbiter(),
                DecisionTraceWriter.NOOP);
        RunStats stats = new DuelSessionRunner(link, port, RunnerConfig.defaults()).run();
        assertEquals(0, stats.answered());
    }

    private static DuelState state(List<CardInstance> hand) {
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "self", 8000, hand, 30, 15,
                List.of(), List.of(), List.of());
        PlayerState opp = new PlayerState(PlayerSide.OPPONENT, 1, "opp", 8000, List.of(), 30, 15,
                List.of(), List.of(), List.of());
        Map<Long, CardInstance> cards = new LinkedHashMap<>();
        hand.forEach(c -> cards.put(c.instanceId(), c));
        return new DuelState(5, 1, PlayerSide.SELF, Phase.MAIN_PHASE_1,
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, opp), cards, List.of(), null, 0);
    }

    private static CardInstance hand(long id, long passcode) {
        return new CardInstance(id, CardIdentity.Known.of(passcode),
                new ZoneRef(ZoneKind.HAND, PlayerSide.SELF, 0), null, Map.of(), List.of());
    }

    private static CardInstance monster(long id, long passcode) {
        return new CardInstance(id, CardIdentity.Known.of(passcode),
                ZoneRef.mainMonster(PlayerSide.SELF, 0), Position.FACE_UP_ATTACK, Map.of(), List.of());
    }

    private static ChoiceRequest.CardChoice choice(CardInstance card) {
        return new ChoiceRequest.CardChoice(card.instanceId(), card.identity(), card.zone());
    }
}
