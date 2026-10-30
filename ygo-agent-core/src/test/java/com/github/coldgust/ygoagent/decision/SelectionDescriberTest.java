package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.map.SelectionDescriber;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals("放到 我方主怪兽区#3", describer.describe(request, selection));
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
    void describesChainDeclineAndRespond() {
        var card = hand(ENGAGE, 1);
        var option = new ChoiceRequest.ChainRequest.ChainOption(
                new ChoiceRequest.EffectChoice(card, 42), false);
        var request = new ChoiceRequest.ChainRequest(List.of(option));

        assertEquals("不响应", describer.describe(request,
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
