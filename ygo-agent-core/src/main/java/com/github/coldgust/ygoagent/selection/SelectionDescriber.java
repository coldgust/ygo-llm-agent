package com.github.coldgust.ygoagent.selection;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;

import java.util.List;
import java.util.StringJoiner;
import java.util.function.LongFunction;

public final class SelectionDescriber {

    private final LongFunction<String> nameOf;

    public SelectionDescriber() {
        this(null);
    }

    public SelectionDescriber(LongFunction<String> nameOf) {
        this.nameOf = nameOf;
    }

    public String describe(ChoiceRequest request, ChoiceSelection selection) {
        if (selection == null) {
            return "（空应答）";
        }
        return switch (selection) {
            case ChoiceSelection.Cancel ignored -> "取消";
            // 动作记录本身也实现了 ChoiceSelection（既有 XxxSelection 包装，也有裸动作）
            case ChoiceSelection.MainPhaseAction a -> action(a);
            case ChoiceSelection.MainPhaseSelection s -> action(s.action());
            case ChoiceSelection.BattlePhaseAction a -> battle(a);
            case ChoiceSelection.BattlePhaseSelection s -> battle(s.action());
            case ChoiceSelection.UnselectStep s -> unselect(s);
            case ChoiceSelection.ChainAnswer a -> chain(a);
            case ChoiceSelection.CardSelection s -> "选择 " + cards(s.selected());
            case ChoiceSelection.TributeSelection s -> "解放 "
                    + join(s.tributed().stream().map(t -> card(t.card())).toList());
            case ChoiceSelection.SumSelection s -> "选卡 " + cards(s.selected());
            case ChoiceSelection.UnselectSelection s -> unselect(s.step());
            case ChoiceSelection.ChainSelection s -> chain(s.answer());
            case ChoiceSelection.PlaceSelection s -> "放到 " + zones(s.zones());
            case ChoiceSelection.PositionSelection s -> position(s.position());
            case ChoiceSelection.EffectYesNoSelection s -> s.activate() ? "发动" : "不发动";
            case ChoiceSelection.YesNoSelection s -> s.yes() ? "是" : "否";
            case ChoiceSelection.OptionSelection s -> "选项 #" + s.index();
            case ChoiceSelection.DeclareRaceSelection s -> "宣言种族 " + join(
                    s.races().stream().map(Enum::name).toList());
            case ChoiceSelection.DeclareAttributeSelection s -> "宣言属性 " + join(
                    s.attributes().stream().map(Enum::name).toList());
            case ChoiceSelection.DeclareCardSelection s -> "宣言卡 " + name(s.passcode());
            case ChoiceSelection.DeclareNumberSelection s -> "宣言数值 #" + s.index();
            case ChoiceSelection.SortSelection s -> "排序 " + cards(s.ordered());
            case ChoiceSelection.CounterSelection s -> "移除指示物 " + s.removals().entrySet().stream()
                    .map(e -> card(e.getKey()) + "×" + e.getValue())
                    .reduce((a, b) -> a + "、" + b).orElse("无");
        };
    }


    private String action(ChoiceSelection.MainPhaseAction action) {
        return switch (action) {
            case ChoiceSelection.Summon a -> "通常召唤 " + card(a.card());
            case ChoiceSelection.SpecialSummon a -> "特殊召唤 " + card(a.card());
            case ChoiceSelection.Reposition a -> "变更表示形式 " + card(a.card());
            case ChoiceSelection.SetMonster a -> "盖放怪兽 " + card(a.card());
            case ChoiceSelection.SetSpellTrap a -> "盖放魔陷 " + card(a.card());
            case ChoiceSelection.Activate a -> "发动 " + card(a.effect().card()) + " 的效果";
            case ChoiceSelection.EnterBattlePhase ignored -> "进入战斗阶段";
            case ChoiceSelection.EndTurn ignored -> "结束回合";
            case ChoiceSelection.ShuffleHand ignored -> "洗牌";
        };
    }

    private String battle(ChoiceSelection.BattlePhaseAction action) {
        return switch (action) {
            case ChoiceSelection.BattleActivate a -> "发动 " + card(a.effect().card()) + " 的效果";
            case ChoiceSelection.Attack a -> "攻击 " + card(a.target().card())
                    + (a.target().directAttack() ? "（直接攻击）" : "");
            case ChoiceSelection.EnterMainPhase2 ignored -> "进入主要阶段 2";
            case ChoiceSelection.EndBattlePhase ignored -> "结束战斗阶段";
        };
    }

    private String unselect(ChoiceSelection.UnselectStep step) {
        return switch (step) {
            case ChoiceSelection.UnselectStep.Select s -> "选中 " + card(s.card());
            case ChoiceSelection.UnselectStep.Unselect s -> "取消选中 " + card(s.card());
            case ChoiceSelection.UnselectStep.Finish ignored -> "结束选择";
        };
    }

    private String chain(ChoiceSelection.ChainAnswer answer) {
        return switch (answer) {
            case ChoiceSelection.ChainAnswer.Respond r -> "发动 "
                    + card(r.option().effect().card())
                    + (r.option().effect().description() != 0
                    ? "（效果#" + r.option().effect().description() + "）" : "");
            case ChoiceSelection.ChainAnswer.Decline ignored -> "不响应";
        };
    }


    private String card(ChoiceRequest.CardChoice choice) {
        if (choice == null) {
            return "?";
        }
        long passcode = choice.identity() == null ? 0 : choice.identity().passcode();
        String label = passcode == 0 ? "盖卡" : name(passcode);
        return choice.zone() == null ? label : label + zone(choice.zone());
    }

    private String cards(List<ChoiceRequest.CardChoice> list) {
        return join(list.stream().map(this::card).toList());
    }

    private String name(long passcode) {
        if (passcode <= 0) {
            return "未知卡";
        }
        if (nameOf != null) {
            try {
                String name = nameOf.apply(passcode);
                if (name != null && !name.isBlank()) {
                    return name + "(" + passcode + ")";
                }
            } catch (RuntimeException ignored) {
                // 名称只是展示，查表失败不该影响任何决策
            }
        }
        return "卡(" + passcode + ")";
    }

    private String zones(List<ZoneRef> zones) {
        return join(zones.stream().map(this::zone).toList());
    }

    /**
     * 区域描述统一走 {@link ZoneCodes#label}：格子序号从 1 起、与计划里的 zone 编码同一套。
     */
    private String zone(ZoneRef zone) {
        return ZoneCodes.label(zone);
    }

    private static String position(Position position) {
        return switch (position) {
            case FACE_UP_ATTACK -> "表侧攻击表示";
            case FACE_DOWN_ATTACK -> "里侧攻击表示";
            case FACE_UP_DEFENSE -> "表侧守备表示";
            case FACE_DOWN_DEFENSE -> "里侧守备表示";
        };
    }

    private static String join(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "（无）";
        }
        StringJoiner sj = new StringJoiner("、");
        parts.forEach(sj::add);
        return sj.toString();
    }
}
