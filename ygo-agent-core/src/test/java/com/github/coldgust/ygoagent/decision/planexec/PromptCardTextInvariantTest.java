package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.model.Candidates;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.planexec.prompt.CardText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不变量：<b>凡是给模型看的卡面/效果，都必须带得出自然语言，且不许出现裸效果码。</b>
 */
class PromptCardTextInvariantTest {

    private final CardFacts facts = TestDuels.facts();
    private final ContextPackBuilder builder = new ContextPackBuilder(facts, DuelMathService.instance());
    private final CardText cardText = new CardText(facts);

    /** 四种会渲染效果文本的决策点，逐一扫不变量。 */
    @Test
    void noPromptEverShowsARawEffectCodeOrAnEmptyEffectText() {
        List<String> packs = List.of(
                effectYesNoPack(),
                chainPack(),
                mainPhasePack(),
                cardSelectPack());

        for (String pack : packs) {
            assertFalse(pack.matches("(?s).*\\[效果#\\d+].*"),
                    "不许把引擎效果码甩给模型（它读不懂，只能猜）:\n" + pack);
            // 只针对"点到具体一张卡"的行（形如 `C1 闪刀起动-交闪(25048511)：`）：
            // 这类行冒号后为空，等于只说"这张卡有个效果"却不说是什么 —— 正是零衣①那次事故的形态。
            for (String line : pack.split("\n")) {
                assertFalse(line.matches(".*\\(\\d{5,}\\).*：\\s*$"),
                        "点到具体卡的效果行不许留白（留白 = 让模型自己编）: " + line);
            }
        }
    }

    /** 能定位条款时，必须给那一条；定位不到时必须列全部，而不是空。 */
    @Test
    void firedClauseIsNeverEmptyForAKnownCard() {
        // 零衣两句都能发动：序号 0 → ①，序号 1 → ②
        assertTrue(cardText.fired(TestDuels.RAY, TestDuels.RAY * 16).contains("①：自己·对方回合"));
        assertTrue(cardText.fired(TestDuels.RAY, TestDuels.RAY * 16 + 1).contains("②：这张卡在墓地存在的状态"));

        // 效果码解不出（比如 0 或超界）：退化为"列出全部条款"，仍然有内容
        for (long code : new long[]{0L, TestDuels.RAY * 16 + 9}) {
            String text = cardText.fired(TestDuels.RAY, code);
            assertFalse(text.isBlank(), "解不出也必须给全部条款: code=" + code);
            assertTrue(text.contains("①："), "解不出时要列全部条款: " + text);
        }

        // 卡库没有这张卡：明说缺资料，而不是空字符串（空字符串会让模型凭印象编）
        String missing = cardText.fired(999_999_999L, 1L);
        assertTrue(missing.contains("卡库无"), "未知卡要明说缺资料: " + missing);
    }

    /** 效果码的序号只数"能发动"的条款——王之仆人 ① 是"当作黑魔术师"、不占号。 */
    @Test
    void effectIndexCountsOnlyActivatableClauses() {
        String second = cardText.fired(TestDuels.SERVANT, TestDuels.SERVANT * 16);
        assertTrue(second.contains("②：这张卡在手卡存在"), "序号 0 应是 ②（① 不占号）: " + second);

        String third = cardText.fired(TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1);
        assertTrue(third.contains("③：自己·对方回合"), "序号 1 应是 ③: " + third);
    }

    /** "是否发动"带的是提示文 id，不是效果序号：不许按低 4 位硬读（97 的低 4 位是 1 → 会读成"②"）。 */
    @Test
    void hintIdsAreNotMistakenForEffectIndexes() {
        assertTrue(cardText.clauseOf(TestDuels.CYANOS, 0L).isEmpty(), "0 不是效果码");
        assertTrue(cardText.clauseOf(TestDuels.CYANOS, 221L).isEmpty(), "221 是提示文 id，不是效果码");
        assertTrue(cardText.clauseOf(TestDuels.CYANOS, 97L).isEmpty(),
                "97 = 6<<4|1：高位不是卡号，不验就会把多效果卡说成'本次是 ②'");
        // 真正属于这张卡的效果码仍然能定位：序号 1 = 第 2 个可发动条款 = ②
        assertTrue(cardText.clauseOf(TestDuels.CYANOS, TestDuels.CYANOS * 16 + 1)
                .map(c -> c.contains("②：这张卡召唤·特殊召唤的场合")).orElse(false));
    }


    private String effectYesNoPack() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 7000, 8000);
        ChoiceRequest.CardChoice ray = new ChoiceRequest.CardChoice(1, TestDuels.known(TestDuels.RAY),
                ZoneRef.mainMonster(PlayerSide.SELF, 0));
        ChoiceRequest request = new ChoiceRequest.EffectYesNoRequest(ray, TestDuels.RAY * 16 + 1);
        return builder.candidatesSection(point(24, state, request, RequestKind.EFFECT_YES_NO));
    }

    private String chainPack() {
        ChainNode link = new ChainNode(1, TestDuels.known(TestDuels.SERVANT), TestDuels.SERVANT * 16 + 1,
                ZoneRef.mainMonster(PlayerSide.OPPONENT, 0), PlayerSide.OPPONENT);
        DuelState base = TestDuels.state(2, false, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.SERVANT, ZoneRef.mainMonster(PlayerSide.OPPONENT, 0))),
                List.of(), 8000, 8000);
        DuelState state = new DuelState(base.duelRule(), base.turnNumber(), base.turnPlayer(), base.phase(),
                base.players(), base.cards(), List.of(link), base.result(), base.selfSeat());

        ChoiceRequest.CardChoice engage = new ChoiceRequest.CardChoice(2, TestDuels.known(TestDuels.ENGAGE),
                ZoneRef.hand(PlayerSide.SELF));
        ChoiceRequest request = new ChoiceRequest.ChainRequest(List.of(
                new ChoiceRequest.ChainRequest.ChainOption(
                        new ChoiceRequest.EffectChoice(engage, TestDuels.ENGAGE * 16), false)));
        return builder.candidatesSection(point(25, state, request, RequestKind.CHAIN));
    }

    private String mainPhasePack() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new ChoiceRequest.EffectChoice(
                        TestDuels.cardChoice(2, TestDuels.ENGAGE), TestDuels.ENGAGE * 16)),
                false, true, false);
        return builder.candidatesSection(point(14, state, request, RequestKind.MAIN_PHASE));
    }

    private String cardSelectPack() {
        DuelState state = TestDuels.state(3, true, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.DRAGOON, ZoneRef.mainMonster(PlayerSide.OPPONENT, 4))),
                List.of(), 8000, 8000);
        List<ChoiceRequest.CardChoice> choices = List.of(
                new ChoiceRequest.CardChoice(1, TestDuels.known(TestDuels.DRAGOON),
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 4)),
                new ChoiceRequest.CardChoice(2, CardIdentity.UNKNOWN,
                        ZoneRef.spellTrap(PlayerSide.OPPONENT, 0)));
        ChoiceRequest request = new ChoiceRequest.CardSelectRequest(1, 1, false, choices);
        return builder.candidatesSection(point(67, state, request, RequestKind.CARD_SELECT));
    }

    private DecisionPoint point(int seq, DuelState state, ChoiceRequest request, RequestKind kind) {
        return new DecisionPoint(seq, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", kind);
    }
}
