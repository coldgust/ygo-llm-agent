package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.model.Candidates;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class TestFixtures {

    public static DecisionPoint point(ChoiceRequest request, DuelState state) {
        return new DecisionPoint(1, 1, state.phase(), PlayerSide.SELF, request, state,
                Candidates.from(request), "state-hash", "request-hash", RequestKind.of(request));
    }

    public static DuelState state(List<CardInstance> hand, List<CardInstance> monsters, int selfLp, int oppLp) {
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "self", selfLp, hand, 30, 15, List.of(), List.of(), List.of());
        PlayerState opp = new PlayerState(PlayerSide.OPPONENT, 1, "opp", oppLp, List.of(), 30, 15, List.of(), List.of(), List.of());
        Map<Long, CardInstance> cards = new LinkedHashMap<>();
        hand.forEach(c -> cards.put(c.instanceId(), c));
        monsters.forEach(c -> cards.put(c.instanceId(), c));
        return new DuelState(5, 1, PlayerSide.SELF, Phase.MAIN_PHASE_1,
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, opp), cards, List.of(), null, 0);
    }

    public static CardInstance handCard(long id, long passcode) {
        return new CardInstance(id, CardIdentity.Known.of(passcode),
                new ZoneRef(ZoneKind.HAND, PlayerSide.SELF, 0),
                null, Map.of(), List.of());
    }

    public static CardInstance monster(long id, long passcode, int sequence) {
        return new CardInstance(id, CardIdentity.Known.of(passcode),
                ZoneRef.mainMonster(PlayerSide.SELF, sequence), Position.FACE_UP_ATTACK, Map.of(), List.of());
    }

    public static CardInstance monsterOf(PlayerSide side, long id, long passcode, int sequence) {
        return new CardInstance(id, CardIdentity.Known.of(passcode),
                ZoneRef.mainMonster(side, sequence), Position.FACE_UP_ATTACK, Map.of(), List.of());
    }

    public static ChoiceRequest.CardChoice choice(CardInstance card) {
        return new ChoiceRequest.CardChoice(card.instanceId(), card.identity(), card.zone());
    }

    private TestFixtures() {
    }
}
