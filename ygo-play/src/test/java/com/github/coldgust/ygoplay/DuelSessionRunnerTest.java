package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionExplanation;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.PathKind;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.model.Timing;
import com.github.coldgust.ygoagent.decision.port.DecisionPort;
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
import com.github.coldgust.ygoagent.decision.arbiter.SimpleArbiter;
import com.github.coldgust.ygoagent.decision.engine.DefaultDecisionPort;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.decision.tactical.TacticalPlannerImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        // 实机死循环：主阶段特招 → 选素材（Unselect min=1）→ 取消 → 回主阶段 → 再特招…127 次。
        // 取消"至少选 1 张"的选择 = 回滚上一步，引擎会重问；映射层必须判非法并兜底成"选第一张"。
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
        DecisionPort alwaysCancel =
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
        // 真实死循环是"两个动作交替"：主阶段反复特招 ↔ 选择时反复取消。
        // 连续判重永远命中不了，必须用窗口判重。
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
        DecisionPort port = (point, deadline) -> {
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
        // 前端原来只显示"决策路径 + 意图类型"，看不出到底放了哪个区。
        // 运行器必须把最终应答翻成一句话交出去。
        CardInstance summoned = hand(1, 89631139);
        DuelState state = state(List.of(summoned));
        var summonChoice = choice(summoned);
        ChoiceRequest main = new ChoiceRequest.MainPhaseRequest(
                List.of(summonChoice), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, true, false);
        ChoiceRequest place = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 2)));

        FakeSessionLink link = new FakeSessionLink(state, List.of(main, place));
        DefaultDecisionPort port = new DefaultDecisionPort(
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
        DefaultDecisionPort port = new DefaultDecisionPort(
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
        DefaultDecisionPort port = new DefaultDecisionPort(
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
