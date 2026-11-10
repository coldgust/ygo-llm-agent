package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygoagent.decision.plan.CandidateIntents;
import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.decision.map.SelectionException;
import com.github.coldgust.ygoagent.decision.map.SelectionMapperImpl;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectionMapperTest {

    private final SelectionMapperImpl mapper = new SelectionMapperImpl();

    @Test
    void mapsSummonWithSameCandidateReference() {
        var card = TestFixtures.handCard(1, 123);
        var choice = TestFixtures.choice(card);
        var request = new ChoiceRequest.MainPhaseRequest(List.of(choice), List.of(), List.of(),
                List.of(), List.of(), List.of(), true, true, false);
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        CandidateId id = point.candidatesOf(Candidate.CardC.class).getFirst().id();

        ChoiceSelection selection = mapper.map(point, new ChoiceIntent.Summon(id));

        var main = (ChoiceSelection.MainPhaseSelection) selection;
        var summon = (ChoiceSelection.Summon) main.action();
        assertSame(choice, summon.card());
    }

    @Test
    void mapsPlaceWithSameZoneReference() {
        ZoneRef zone = ZoneRef.mainMonster(PlayerSide.SELF, 2);
        var request = new ChoiceRequest.PlaceRequest(1, List.of(zone));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        CandidateId id = point.candidatesOf(Candidate.ZoneC.class).getFirst().id();

        ChoiceSelection selection = mapper.map(point, new ChoiceIntent.Place(List.of(id)));

        var place = (ChoiceSelection.PlaceSelection) selection;
        assertSame(zone, place.zones().getFirst());
    }

    @Test
    void mapsZeroCountPlaceWithOneZone() {
        ZoneRef zone = ZoneRef.mainMonster(PlayerSide.SELF, 0);
        var request = new ChoiceRequest.PlaceRequest(0, List.of(zone));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        CandidateId id = point.candidatesOf(Candidate.ZoneC.class).getFirst().id();

        var place = (ChoiceSelection.PlaceSelection) mapper.map(point, new ChoiceIntent.Place(List.of(id)));
        assertEquals(1, place.zones().size());
        assertSame(zone, place.zones().getFirst());
    }

    @Test
    void mapsChainDeclineAndRespond() {
        var card = TestFixtures.handCard(1, 123);
        var effect = new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0);
        var option = new ChoiceRequest.ChainRequest.ChainOption(effect, false);
        var request = new ChoiceRequest.ChainRequest(List.of(option));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        CandidateId effectId = point.candidatesOf(Candidate.EffectC.class).getFirst().id();

        var decline = (ChoiceSelection.ChainSelection) mapper.map(point, new ChoiceIntent.ChainDecline());
        assertSame(ChoiceSelection.ChainAnswer.Decline.INSTANCE, decline.answer());

        var respond = (ChoiceSelection.ChainSelection) mapper.map(point, new ChoiceIntent.ChainRespond(effectId));
        var answer = (ChoiceSelection.ChainAnswer.Respond) respond.answer();
        assertSame(option, answer.option());
    }

    @Test
    void activateOnChainRequestIsEncodedAsRespondNotDecline() {
        var card = TestFixtures.handCard(1, 123);
        var effect = new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0);
        var option = new ChoiceRequest.ChainRequest.ChainOption(effect, false);
        var request = new ChoiceRequest.ChainRequest(List.of(option));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        CandidateId effectId = point.candidatesOf(Candidate.EffectC.class).getFirst().id();

        var selection = (ChoiceSelection.ChainSelection) mapper.map(
                point, new ChoiceIntent.Activate(effectId));

        assertInstanceOf(ChoiceSelection.ChainAnswer.Respond.class, selection.answer(),
                "连锁窗口上的 Activate 必须编码成发动响应，不能退化成弃权");
    }

    @Test
    void chainCandidateBecomesChainRespondIntent() {
        // 候选 → 意图这一步也要正确：连锁窗口的 EffectC 必须产出 ChainRespond
        var card = TestFixtures.handCard(1, 123);
        var effect = new ChoiceRequest.EffectChoice(TestFixtures.choice(card), 0);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(effect, false)));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        var candidate = point.candidatesOf(Candidate.EffectC.class).getFirst();

        var intent = CandidateIntents
                .toIntent(point, candidate).orElseThrow();

        assertInstanceOf(ChoiceIntent.ChainRespond.class, intent,
                "连锁候选应产出 ChainRespond，而不是 Activate");
    }

    @Test
    void mapsCardSelectInRange() {
        var c1 = TestFixtures.handCard(1, 111);
        var c2 = TestFixtures.handCard(2, 222);
        var request = new ChoiceRequest.CardSelectRequest(1, 2, false,
                List.of(TestFixtures.choice(c1), TestFixtures.choice(c2)));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(c1, c2), List.of(), 8000, 8000));
        List<CandidateId> ids = point.candidatesOf(Candidate.CardC.class).stream().map(Candidate.CardC::id).toList();

        var selection = (ChoiceSelection.CardSelection) mapper.map(point, new ChoiceIntent.Select(ids));
        assertEquals(2, selection.selected().size());
        assertSame(request.candidates().get(0), selection.selected().get(0));
    }

    @Test
    void rejectsInvalidMapping() {
        var card = TestFixtures.handCard(1, 123);
        var request = new ChoiceRequest.CardSelectRequest(1, 1, false, List.of(TestFixtures.choice(card)));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        CandidateId id = point.candidatesOf(Candidate.CardC.class).getFirst().id();

        assertThrows(SelectionException.class, () -> mapper.map(point, new ChoiceIntent.Summon(id)));
        assertThrows(SelectionException.class, () -> mapper.map(point, new ChoiceIntent.Cancel()));
    }

    @Test
    void mapsDeclareAndCounter() {
        var c1 = TestFixtures.handCard(1, 111);
        var c2 = TestFixtures.handCard(2, 222);
        var raceRequest = new ChoiceRequest.DeclareRaceRequest(1, Set.of(MonsterRace.DRAGON));
        var racePoint = TestFixtures.point(raceRequest, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var race = (ChoiceSelection.DeclareRaceSelection) mapper.map(racePoint,
                new ChoiceIntent.DeclareRace(Set.of(MonsterRace.DRAGON)));
        assertEquals(Set.of(MonsterRace.DRAGON), race.races());

        var attrRequest = new ChoiceRequest.DeclareAttributeRequest(1, Set.of(Attribute.LIGHT));
        var attrPoint = TestFixtures.point(attrRequest, TestFixtures.state(List.of(), List.of(), 8000, 8000));
        var attr = (ChoiceSelection.DeclareAttributeSelection) mapper.map(attrPoint,
                new ChoiceIntent.DeclareAttribute(Set.of(Attribute.LIGHT)));
        assertEquals(Set.of(Attribute.LIGHT), attr.attributes());

        var counterRequest = new ChoiceRequest.CounterRequest(1, 2,
                List.of(new ChoiceRequest.CounterRequest.CounterChoice(TestFixtures.choice(c1), 3)));
        var counterPoint = TestFixtures.point(counterRequest, TestFixtures.state(List.of(c1, c2), List.of(), 8000, 8000));
        CandidateId counterId = counterPoint.candidatesOf(Candidate.CounterC.class).getFirst().id();
        var counter = (ChoiceSelection.CounterSelection) mapper.map(counterPoint,
                new ChoiceIntent.Counter(Map.of(counterId, 2)));
        assertEquals(2, counter.removals().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void mapsPosition() {
        var card = TestFixtures.handCard(1, 123);
        var request = new ChoiceRequest.PositionRequest(TestFixtures.choice(card),
                Set.of(Position.FACE_UP_ATTACK, Position.FACE_UP_DEFENSE));
        var point = TestFixtures.point(request, TestFixtures.state(List.of(card), List.of(), 8000, 8000));
        var selection = (ChoiceSelection.PositionSelection) mapper.map(point,
                new ChoiceIntent.PositionChoice(Position.FACE_UP_DEFENSE));
        assertEquals(Position.FACE_UP_DEFENSE, selection.position());
    }
}
