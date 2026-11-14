package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.TestDuels;

class DuelMathServiceTest {

    private final DuelMathService math = new DuelMathService();
    private final CardFacts facts = TestDuels.facts();

    private static CardInstance monster(long id, long passcode, PlayerSideOf side, int seq, Position pos) {
        var owner = side == PlayerSideOf.SELF
                ? PlayerSide.SELF
                : PlayerSide.OPPONENT;
        return new CardInstance(id, TestDuels.known(passcode),
                ZoneRef.mainMonster(owner, seq), pos, Map.of(), List.of());
    }

    private enum PlayerSideOf { SELF, OPPONENT }

    @Test
    void attackDestroysWeakerDefenseTarget() {
        // 我 2500 攻击 对方 2000（守备表示）→ 破坏，无贯穿无伤害
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, 70902743L, PlayerSideOf.OPPONENT, 0, Position.FACE_UP_DEFENSE)),
                List.of(), 8000, 8000);
        List<DuelMathService.AttackLine> table = math.attackTable(state, facts);
        assertEquals(1, table.size());
        assertEquals(DuelMathService.AttackLine.Outcome.DESTROY_AND_DAMAGE, table.getFirst().outcome());
        assertEquals(0, table.getFirst().damageToOpponent());
    }

    @Test
    void attackDestroysWeakerAttackTargetWithDamage() {
        // 我 2500 攻击 对方 1500（攻击表示）→ 破坏并造成 1000
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, TestDuels.HORNET, PlayerSideOf.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        DuelMathService.AttackLine line = math.attackTable(state, facts).getFirst();
        assertEquals(DuelMathService.AttackLine.Outcome.DESTROY_AND_DAMAGE, line.outcome());
        assertEquals(1000, line.damageToOpponent());
    }

    @Test
    void attackIntoStrongerAttackerDestroyed() {
        // 我 2500 攻击 对方 3000（攻击表示）→ 攻击方被破坏，我受 500
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, 70902743L, PlayerSideOf.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        DuelMathService.AttackLine line = math.attackTable(state, facts).getFirst();
        assertEquals(DuelMathService.AttackLine.Outcome.ATTACKER_DESTROYED, line.outcome());
        assertEquals(500, line.damageToSelf());
    }

    @Test
    void attackIntoStrongerDefenseNoEffect() {
        // 我 2500 攻击 对方 巨盾守卫（守备 DEF 3000）→ 打不动，无伤害
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, 112240363L, PlayerSideOf.OPPONENT, 0, Position.FACE_UP_DEFENSE)),
                List.of(), 8000, 8000);
        DuelMathService.AttackLine line = math.attackTable(state, facts).getFirst();
        assertEquals(DuelMathService.AttackLine.Outcome.NO_EFFECT, line.outcome());
        assertEquals(0, line.damageToOpponent());
        assertFalse(math.lethalScan(state, facts).directLethal());
    }

    @Test
    void directAttackWhenOpponentFieldEmpty() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 2000);
        DuelMathService.AttackLine line = math.attackTable(state, facts).getFirst();
        assertEquals(DuelMathService.AttackLine.Outcome.DIRECT_ATTACK, line.outcome());
        assertEquals(2500, line.damageToOpponent());
        DuelMathService.LethalScan scan = math.lethalScan(state, facts);
        assertTrue(scan.directLethal());
        assertEquals(2500, scan.totalAvailableDamage());
    }

    @Test
    void unknownFaceDownTargetUnresolved() {
        CardInstance faceDown = new CardInstance(5, CardIdentity.UNKNOWN,
                ZoneRef.mainMonster(PlayerSide.OPPONENT, 0),
                Position.FACE_DOWN_DEFENSE, Map.of(), List.of());
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSideOf.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(faceDown), List.of(), 8000, 8000);
        assertEquals(DuelMathService.AttackLine.Outcome.UNRESOLVED,
                math.attackTable(state, facts).getFirst().outcome());
        assertFalse(math.lethalScan(state, facts).directLethal());
    }
}
