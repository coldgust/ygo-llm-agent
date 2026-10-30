package com.github.coldgust.ygodomain.decision;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对 {@link ChoiceRequest} 的决策结果。每个 selection 与一个请求一一对应，
 * 全部引用请求给出的候选对象（record 相等性保证不会引用到不存在的选项）；
 * SDK 负责将其编码为协议应答。
 */
public sealed interface ChoiceSelection {

    /** 取消 / 放弃（仅当请求允许时合法）。 */
    record Cancel() implements ChoiceSelection {
        public static final Cancel INSTANCE = new Cancel();
    }

    /* ================= 主要阶段 ================= */

    /** 主阶段的一个动作。 */
    sealed interface MainPhaseAction permits Summon, SpecialSummon, Reposition,
            SetMonster, SetSpellTrap, Activate, EnterBattlePhase, EndTurn, ShuffleHand {
    }

    record Summon(ChoiceRequest.CardChoice card) implements MainPhaseAction, ChoiceSelection {
    }

    record SpecialSummon(ChoiceRequest.CardChoice card) implements MainPhaseAction, ChoiceSelection {
    }

    record Reposition(ChoiceRequest.CardChoice card) implements MainPhaseAction, ChoiceSelection {
    }

    record SetMonster(ChoiceRequest.CardChoice card) implements MainPhaseAction, ChoiceSelection {
    }

    record SetSpellTrap(ChoiceRequest.CardChoice card) implements MainPhaseAction, ChoiceSelection {
    }

    record Activate(ChoiceRequest.EffectChoice effect) implements MainPhaseAction, ChoiceSelection {
    }

    record EnterBattlePhase() implements MainPhaseAction, ChoiceSelection {
        public static final EnterBattlePhase INSTANCE = new EnterBattlePhase();
    }

    record EndTurn() implements MainPhaseAction, ChoiceSelection {
        public static final EndTurn INSTANCE = new EndTurn();
    }

    record ShuffleHand() implements MainPhaseAction, ChoiceSelection {
        public static final ShuffleHand INSTANCE = new ShuffleHand();
    }

    record MainPhaseSelection(MainPhaseAction action) implements ChoiceSelection {
    }

    /* ================= 战斗阶段 ================= */

    /** 战斗阶段的一个动作。 */
    sealed interface BattlePhaseAction permits BattleActivate, Attack, EnterMainPhase2, EndBattlePhase {
    }

    record BattleActivate(ChoiceRequest.EffectChoice effect) implements BattlePhaseAction, ChoiceSelection {
    }

    record Attack(ChoiceRequest.AttackChoice target) implements BattlePhaseAction, ChoiceSelection {
    }

    record EnterMainPhase2() implements BattlePhaseAction, ChoiceSelection {
        public static final EnterMainPhase2 INSTANCE = new EnterMainPhase2();
    }

    record EndBattlePhase() implements BattlePhaseAction, ChoiceSelection {
        public static final EndBattlePhase INSTANCE = new EndBattlePhase();
    }

    record BattlePhaseSelection(BattlePhaseAction action) implements ChoiceSelection {
    }

    /* ================= 选卡族 ================= */

    /** 选卡：selected 为所选手卡（数量须在 [min, max] 内）。 */
    record CardSelection(List<ChoiceRequest.CardChoice> selected) implements ChoiceSelection {
        public CardSelection {
            selected = selected == null ? List.of() : List.copyOf(selected);
        }

        public static CardSelection of(ChoiceRequest.CardChoice... cards) {
            return new CardSelection(List.of(cards));
        }
    }

    /** 选祭品：releaseCount 之和须满足要求。 */
    record TributeSelection(List<ChoiceRequest.TributeChoice> tributed) implements ChoiceSelection {
        public TributeSelection {
            tributed = tributed == null ? List.of() : List.copyOf(tributed);
        }
    }

    /** 按数值和选卡：selected = 必选卡（按请求顺序）+ 所选可选卡。 */
    record SumSelection(List<ChoiceRequest.CardChoice> selected) implements ChoiceSelection {
        public SumSelection {
            selected = selected == null ? List.of() : List.copyOf(selected);
        }
    }

    /** 选卡 / 取消已选的单步动作。 */
    sealed interface UnselectStep extends ChoiceSelection {

        /** 选中候选中的这张卡。 */
        record Select(ChoiceRequest.CardChoice card) implements UnselectStep {
        }

        /** 反悔：把已选中的这张卡取消。 */
        record Unselect(ChoiceRequest.CardChoice card) implements UnselectStep {
        }

        /** 结束本次选择（须 finishable / cancelable）。 */
        record Finish() implements UnselectStep {
            public static final Finish INSTANCE = new Finish();
        }
    }

    record UnselectSelection(UnselectStep step) implements ChoiceSelection {
    }

    /* ================= 连锁 ================= */

    /** 连锁应答：respond = 发动某个效果；decline = 不连锁（有强制效果时非法）。 */
    sealed interface ChainAnswer extends ChoiceSelection {

        /** 发动第 option 个效果。 */
        record Respond(ChoiceRequest.ChainRequest.ChainOption option) implements ChainAnswer {
        }

        /** 不连锁（请求含强制效果时非法）。 */
        record Decline() implements ChainAnswer {
            public static final Decline INSTANCE = new Decline();
        }
    }

    record ChainSelection(ChainAnswer answer) implements ChoiceSelection {
    }

    /* ================= 区域与表示形式 ================= */

    /** 选区域：zones 为选定的格子（数量须等于请求的 count）。 */
    record PlaceSelection(List<ZoneRef> zones) implements ChoiceSelection {
        public PlaceSelection {
            zones = zones == null ? List.of() : List.copyOf(zones);
        }

        public static PlaceSelection of(ZoneRef... zones) {
            return new PlaceSelection(List.of(zones));
        }
    }

    /** 选表示形式。 */
    record PositionSelection(Position position) implements ChoiceSelection {
    }

    /* ================= 是/否与选项 ================= */

    record EffectYesNoSelection(boolean activate) implements ChoiceSelection {
    }

    record YesNoSelection(boolean yes) implements ChoiceSelection {
    }

    /** 多选一：index 为候选下标。 */
    record OptionSelection(int index) implements ChoiceSelection {
    }

    /* ================= 宣言族 ================= */

    record DeclareRaceSelection(Set<MonsterRace> races) implements ChoiceSelection {
        public DeclareRaceSelection {
            races = races == null ? Set.of() : Set.copyOf(races);
        }
    }

    record DeclareAttributeSelection(Set<Attribute> attributes) implements ChoiceSelection {
        public DeclareAttributeSelection {
            attributes = attributes == null ? Set.of() : Set.copyOf(attributes);
        }
    }

    /** 宣言卡名：必须为存在的 passcode。 */
    record DeclareCardSelection(long passcode) implements ChoiceSelection {
    }

    /** 宣言数字：index 为候选下标。 */
    record DeclareNumberSelection(int index) implements ChoiceSelection {
    }

    /* ================= 排序与指示物 ================= */

    /** 排序：ordered 为新顺序（原候选的一个排列）。 */
    record SortSelection(List<ChoiceRequest.CardChoice> ordered) implements ChoiceSelection {
        public SortSelection {
            ordered = ordered == null ? List.of() : List.copyOf(ordered);
        }
    }

    /** 指示物：每张候选卡移除的数量，总和须等于请求的 total。 */
    record CounterSelection(Map<ChoiceRequest.CardChoice, Integer> removals) implements ChoiceSelection {
        public CounterSelection {
            removals = removals == null ? Map.of() : Map.copyOf(removals);
        }
    }
}
