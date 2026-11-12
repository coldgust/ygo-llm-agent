package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.EvidencePack;
import com.github.coldgust.ygoagent.pipeline.fallback.TacticalPlannerImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.github.coldgust.ygoagent.support.TestFixtures;

class TacticalPlannerTest {

    private final TacticalPlannerImpl tactical = new TacticalPlannerImpl();

    @Test
    void prefersSummonOnEmptyBoard() {
        var card = TestFixtures.handCard(1, 123);
        var request = new ChoiceRequest.MainPhaseRequest(List.of(TestFixtures.choice(card)), List.of(),
                List.of(), List.of(), List.of(), List.of(), true, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));

        var ranked = tactical.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertNotNull(ranked.best());
        assertInstanceOf(ChoiceIntent.Summon.class, ranked.best().firstStep());
    }

    @Test
    void prefersDirectAttackInBattle() {
        var attacker = TestFixtures.monster(10, 456, 0);
        var direct = new ChoiceRequest.AttackChoice(TestFixtures.choice(attacker), true);
        var request = new ChoiceRequest.BattlePhaseRequest(List.of(), List.of(direct), true, true);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(attacker), 8000, 8000));

        var ranked = tactical.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertNotNull(ranked.best());
        assertInstanceOf(ChoiceIntent.Attack.class, ranked.best().firstStep());
    }

    @Test
    void endsTurnWhenNothingToDo() {
        var request = new ChoiceRequest.MainPhaseRequest(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));

        var ranked = tactical.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertInstanceOf(ChoiceIntent.EndTurn.class, ranked.best().firstStep());
    }

    @Test
    void returnsEmptyWhenDeadlineExpired() {
        var card = TestFixtures.handCard(1, 123);
        var request = new ChoiceRequest.MainPhaseRequest(List.of(TestFixtures.choice(card)), List.of(),
                List.of(), List.of(), List.of(), List.of(), true, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        var expired = DecisionDeadline.of(0);

        var ranked = tactical.plan(point, EvidencePack.empty(), expired);
        assertEquals(0, ranked.lines().size());
    }

    @Test
    void attackPreferenceUsesDirectFlag() {
        var a1 = TestFixtures.monster(10, 456, 0);
        var a2 = TestFixtures.monster(11, 789, 1);
        var normal = new ChoiceRequest.AttackChoice(TestFixtures.choice(a1), false);
        var direct = new ChoiceRequest.AttackChoice(TestFixtures.choice(a2), true);
        var request = new ChoiceRequest.BattlePhaseRequest(List.of(), List.of(normal, direct), true, true);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(a1, a2), 8000, 8000));

        var ranked = tactical.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertInstanceOf(ChoiceIntent.Attack.class, ranked.best().firstStep());
        var attack = (ChoiceIntent.Attack) ranked.best().firstStep();
        var chosen = point.candidatesOf(Candidate.AttackC.class).stream()
                .filter(c -> c.id().equals(attack.attack())).findFirst().orElseThrow();
        assertEquals(true, chosen.value().directAttack());
    }

    @Test
    void battlePhaseWithNoActionsEndsBattle() {
        var request = new ChoiceRequest.BattlePhaseRequest(List.of(), List.of(), false, true);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var ranked = tactical.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));
        assertInstanceOf(ChoiceIntent.EndBattlePhase.class, ranked.best().firstStep());
        assertEquals(PlayerSide.SELF, point.side());
    }
}
