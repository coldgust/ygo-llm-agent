package com.github.coldgust.ygoagent.pipeline.fallback;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygoagent.pipeline.fallback.SimpleArbiter;
import com.github.coldgust.ygoagent.knowledge.ComboSource;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.EvidencePack;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.pipeline.fallback.TacticalPlannerImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.support.TestFixtures;

class ComboTacticalTest {

    @Test
    void comboHitProducesComboPathAndTopIntent() {
        var card = TestFixtures.handCard(1, 63166095);
        var request = new ChoiceRequest.MainPhaseRequest(List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0)),
                true, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));

        ComboSource source = p -> Optional.of(new ComboSource.ComboHint(
                "SKY-ENGAGE-001",
                new ComboSource.ComboStep("activate", 63166095),
                List.of(),
                "检索+资源循环",
                0.92));
        var planner = new TacticalPlannerImpl(source);

        var ranked = planner.plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertTrue(ranked.best().id().startsWith("COMBO:"));
        assertInstanceOf(ChoiceIntent.Activate.class, ranked.best().firstStep());
        var decision = new SimpleArbiter().choose(point, ranked);
        assertEquals(PathKind.COMBO, decision.pathKind());
    }

    @Test
    void comboMissFallsBackToTactical() {
        var card = TestFixtures.handCard(1, 111);
        var request = new ChoiceRequest.MainPhaseRequest(List.of(TestFixtures.choice(card)), List.of(),
                List.of(), List.of(), List.of(), List.of(), true, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));

        ComboSource source = p -> Optional.of(new ComboSource.ComboHint(
                "SKY-ENGAGE-001",
                new ComboSource.ComboStep("activate", 63166095),
                List.of(), "no-op", 0.92));
        var ranked = new TacticalPlannerImpl(source)
                .plan(point, EvidencePack.empty(), DecisionDeadline.of(500));

        assertInstanceOf(ChoiceIntent.Summon.class, ranked.best().firstStep());
        var decision = new SimpleArbiter().choose(point, ranked);
        assertEquals(PathKind.TACTICAL, decision.pathKind());
    }
}
