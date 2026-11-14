package com.github.coldgust.ygoagent.selection;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.selection.SelectionDescriber;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：界面只显示"决策路径 + 意图类型"时，看不出到底提交了什么
 * （例如「唯一合法 T1 选择位置 → Place」——放了哪个区？没人知道）。
 */
class SelectionDescriberTest {

    private static final long ENGAGE = 63166095L;

    private final SelectionDescriber describer = new SelectionDescriber(
            code -> code == ENGAGE ? "闪刀起动-交闪" : null);

    private static ChoiceRequest.CardChoice hand(long code, int seq) {
        return new ChoiceRequest.CardChoice(seq, CardIdentity.Known.of(code),
                ZoneRef.hand(PlayerSide.SELF));
    }

    @Test
    void describesPlaceWithZoneAndOwner() {
        var request = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 2)));
        var selection = new ChoiceSelection.PlaceSelection(
                List.of(ZoneRef.mainMonster(PlayerSide.SELF, 2)));

        assertEquals("放到 我方主怪兽区3", describer.describe(request, selection));
    }

    @Test
    void describesActivateWithCardName() {
        var card = hand(ENGAGE, 1);
        var request = new ChoiceRequest.MainPhaseRequest(
                List.<ChoiceRequest.CardChoice>of(), List.<ChoiceRequest.CardChoice>of(),
                List.<ChoiceRequest.CardChoice>of(), List.<ChoiceRequest.CardChoice>of(),
                List.<ChoiceRequest.CardChoice>of(),
                List.of(new ChoiceRequest.EffectChoice(card, 1)), true, true, false);
        var selection = new ChoiceSelection.MainPhaseSelection(
                new ChoiceSelection.Activate(new ChoiceRequest.EffectChoice(card, 1)));

        String text = describer.describe(request, selection);
        assertTrue(text.contains("发动"), text);
        assertTrue(text.contains("闪刀起动-交闪") && text.contains(String.valueOf(ENGAGE)), text);
    }

    @Test
    void cardActivationIsNotWordedAsEffectActivation() {
        // 2026-11-15 用户反馈：永续魔法从手牌发动是"发动卡本身"，不能写成"发动…的效果"
        var card = new ChoiceRequest.CardChoice(1, CardIdentity.Known.of(24010609L),
                ZoneRef.hand(PlayerSide.SELF));
        var selection = new ChoiceSelection.Activate(
                new ChoiceRequest.EffectChoice(card, 0));
        String text = describer.describe(null, selection);
        assertTrue(text.contains("发动卡本身"), text);

        // 从盖放状态翻开（魔陷区 + desc=0）同样是发动卡
        var setCard = new ChoiceRequest.CardChoice(2, CardIdentity.Known.of(24010609L),
                ZoneRef.spellTrap(PlayerSide.SELF, 2));
        assertTrue(describer.describe(null,
                new ChoiceSelection.Activate(new ChoiceRequest.EffectChoice(setCard, 0)))
                .contains("发动卡本身"));

        // 本卡效果码（卡号<<4）→ 照常措辞"的效果"
        var effect = new ChoiceRequest.CardChoice(3, CardIdentity.Known.of(24010609L),
                ZoneRef.spellTrap(PlayerSide.SELF, 2));
        String effectText = describer.describe(null,
                new ChoiceSelection.Activate(
                        new ChoiceRequest.EffectChoice(effect, 24010609L * 16)));
        assertTrue(effectText.contains("的效果"),
                "效果码命中本卡时按效果措辞: " + effectText);
        assertFalse(effectText.contains("发动卡本身"), effectText);
    }

    @Test
    void describesChainDeclineAndRespond() {
        var card = hand(ENGAGE, 1);
        var option = new ChoiceRequest.ChainRequest.ChainOption(
                new ChoiceRequest.EffectChoice(card, 42), false);
        var request = new ChoiceRequest.ChainRequest(List.of(option));

        assertEquals("放弃优先权（不发动）", describer.describe(request,
                new ChoiceSelection.ChainSelection(new ChoiceSelection.ChainAnswer.Decline())));
        assertTrue(describer.describe(request,
                new ChoiceSelection.ChainSelection(
                        new ChoiceSelection.ChainAnswer.Respond(option))).contains("效果#42"));
    }

    @Test
    void describesPositionAndBooleanAndEndTurn() {
        assertEquals("表侧守备表示", describer.describe(
                new ChoiceRequest.PositionRequest(hand(ENGAGE, 1), Set.of(Position.FACE_UP_DEFENSE)),
                new ChoiceSelection.PositionSelection(Position.FACE_UP_DEFENSE)));
        assertEquals("结束回合", describer.describe(
                new ChoiceRequest.MainPhaseRequest(
                        List.<ChoiceRequest.CardChoice>of(), List.<ChoiceRequest.CardChoice>of(),
                        List.<ChoiceRequest.CardChoice>of(), List.<ChoiceRequest.CardChoice>of(),
                        List.<ChoiceRequest.CardChoice>of(), List.<ChoiceRequest.EffectChoice>of(),
                        true, true, false),
                new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE)));
        assertEquals("不发动", describer.describe(
                new ChoiceRequest.EffectYesNoRequest(hand(ENGAGE, 1), 1),
                new ChoiceSelection.EffectYesNoSelection(false)));
    }

    @Test
    void fallsBackToPasscodeWhenNameUnknown() {
        var request = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.field(PlayerSide.OPPONENT)));
        assertEquals("放到 对方场地魔法区", describer.describe(request,
                new ChoiceSelection.PlaceSelection(List.of(ZoneRef.field(PlayerSide.OPPONENT)))));

        var unknown = new ChoiceRequest.CardChoice(9, CardIdentity.Known.of(99999999L),
                ZoneRef.graveyard(PlayerSide.SELF));
        String text = describer.describe(new ChoiceRequest.CardSelectRequest(1, 1, false,
                        List.of(unknown)),
                new ChoiceSelection.CardSelection(List.of(unknown)));
        assertTrue(text.contains("卡(99999999)"), text);
    }
}
