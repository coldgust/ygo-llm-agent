package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestKindTest {

    @Test
    void mapsAllNineteenRequestTypes() {
        var card = TestFixtures.choice(TestFixtures.handCard(1, 123));
        var effect = new ChoiceRequest.EffectChoice(card, 0);
        var zone = ZoneRef.mainMonster(PlayerSide.SELF, 0);
        List<ChoiceRequest> requests = List.of(
                new ChoiceRequest.MainPhaseRequest(List.of(card), List.of(), List.of(), List.of(),
                        List.of(), List.of(), true, true, false),
                new ChoiceRequest.BattlePhaseRequest(List.of(), List.of(), true, true),
                new ChoiceRequest.ChainRequest(List.of(new ChoiceRequest.ChainRequest.ChainOption(effect, false))),
                new ChoiceRequest.CardSelectRequest(1, 1, false, List.of(card)),
                new ChoiceRequest.TributeSelectRequest(1, 1, false,
                        List.of(new ChoiceRequest.TributeChoice(card, 1))),
                new ChoiceRequest.SumRequest(false, 4, 1, 1, List.of(card), List.of()),
                new ChoiceRequest.UnselectRequest(false, true, 1, 1, List.of(card), List.of()),
                new ChoiceRequest.PlaceRequest(1, List.of(zone)),
                new ChoiceRequest.DisfieldRequest(1, List.of(zone)),
                new ChoiceRequest.PositionRequest(card, Set.of(Position.FACE_UP_ATTACK)),
                new ChoiceRequest.EffectYesNoRequest(card, 0),
                new ChoiceRequest.YesNoRequest(0),
                new ChoiceRequest.OptionRequest(List.of(0L, 1L)),
                new ChoiceRequest.DeclareRaceRequest(1, Set.of(MonsterRace.DRAGON)),
                new ChoiceRequest.DeclareAttributeRequest(1, Set.of(Attribute.LIGHT)),
                new ChoiceRequest.DeclareCardRequest(List.of(0L, 1073742082L)),
                new ChoiceRequest.DeclareNumberRequest(List.of(1, 2)),
                new ChoiceRequest.SortRequest(List.of(card)),
                new ChoiceRequest.CounterRequest(1, 1, List.of(new ChoiceRequest.CounterRequest.CounterChoice(card, 1)))
        );
        assertEquals(19, requests.size());
        assertEquals(RequestKind.values().length, requests.stream().map(RequestKind::of).distinct().count());
    }
}
