package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.duel.Position;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 决策意图：对候选 id 的选择（不是动作描述）。
 * 与 19 类请求一一对应；由 SelectionMapper 按引用还原成 ChoiceSelection。
 */
public sealed interface ChoiceIntent {

    /**
     * 意图的中文短标签（决策流直接显示用）。
     * 类名（ChainDecline 等）是实现词汇，不该甩给使用者；措辞与 {@code IntentSummary} 保持一致。
     */
    static String label(ChoiceIntent intent) {
        return switch (intent) {
            case ChoiceIntent.Summon ignored -> "通常召唤";
            case ChoiceIntent.SpecialSummon ignored -> "特殊召唤";
            case ChoiceIntent.Reposition ignored -> "改变表示形式";
            case ChoiceIntent.SetMonster ignored -> "盖放怪兽";
            case ChoiceIntent.SetSpellTrap ignored -> "盖放魔陷";
            case ChoiceIntent.Activate ignored -> "发动效果";
            case ChoiceIntent.EnterBattlePhase ignored -> "进入战阶";
            case ChoiceIntent.EndTurn ignored -> "结束回合";
            case ChoiceIntent.ShuffleHand ignored -> "洗手牌";
            case ChoiceIntent.BattleActivate ignored -> "战斗中发动";
            case ChoiceIntent.Attack ignored -> "攻击宣言";
            case ChoiceIntent.EnterMainPhase2 ignored -> "进入主要阶段2";
            case ChoiceIntent.EndBattlePhase ignored -> "结束战阶";
            case ChoiceIntent.ChainDecline ignored -> "不连锁";
            case ChoiceIntent.ChainRespond ignored -> "连锁发动";
            case ChoiceIntent.EffectYesNo y -> y.activate() ? "发动" : "不发动";
            case ChoiceIntent.YesNo y -> y.yes() ? "确认：是" : "确认：否";
            case ChoiceIntent.Option ignored -> "选选项";
            case ChoiceIntent.Select ignored -> "选卡";
            case ChoiceIntent.Tribute ignored -> "选祭品";
            case ChoiceIntent.Sum ignored -> "凑数选择";
            case ChoiceIntent.UnselectSelect ignored -> "选卡";
            case ChoiceIntent.UnselectUndo ignored -> "退回";
            case ChoiceIntent.UnselectFinish ignored -> "完成选择";
            case ChoiceIntent.Cancel ignored -> "取消";
            case ChoiceIntent.Place ignored -> "选位置";
            case ChoiceIntent.PositionChoice ignored -> "选表示形式";
            case ChoiceIntent.DeclareRace ignored -> "宣言种族";
            case ChoiceIntent.DeclareAttribute ignored -> "宣言属性";
            case ChoiceIntent.DeclareCard ignored -> "宣言卡片";
            case ChoiceIntent.DeclareNumber ignored -> "宣言数值";
            case ChoiceIntent.Sort ignored -> "排序";
            case ChoiceIntent.Counter ignored -> "取除指示物";
        };
    }

    /* ---------- 主阶段 ---------- */
    record Summon(CandidateId card) implements ChoiceIntent {
    }

    record SpecialSummon(CandidateId card) implements ChoiceIntent {
    }

    record Reposition(CandidateId card) implements ChoiceIntent {
    }

    record SetMonster(CandidateId card) implements ChoiceIntent {
    }

    record SetSpellTrap(CandidateId card) implements ChoiceIntent {
    }

    record Activate(CandidateId effect) implements ChoiceIntent {
    }

    record EnterBattlePhase() implements ChoiceIntent {
    }

    record EndTurn() implements ChoiceIntent {
    }

    record ShuffleHand() implements ChoiceIntent {
    }

    /* ---------- 战斗阶段 ---------- */
    record BattleActivate(CandidateId effect) implements ChoiceIntent {
    }

    record Attack(CandidateId attack) implements ChoiceIntent {
    }

    record EnterMainPhase2() implements ChoiceIntent {
    }

    record EndBattlePhase() implements ChoiceIntent {
    }

    /* ---------- 连锁 ---------- */
    record ChainDecline() implements ChoiceIntent {
    }

    record ChainRespond(CandidateId option) implements ChoiceIntent {
    }

    /* ---------- 是/否与选项 ---------- */
    record EffectYesNo(boolean activate) implements ChoiceIntent {
    }

    record YesNo(boolean yes) implements ChoiceIntent {
    }

    record Option(int index) implements ChoiceIntent {
    }

    /* ---------- 选卡族 ---------- */
    record Select(List<CandidateId> cards) implements ChoiceIntent {
        public Select {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    record Tribute(List<CandidateId> cards) implements ChoiceIntent {
        public Tribute {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    record Sum(List<CandidateId> cards) implements ChoiceIntent {
        public Sum {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    record UnselectSelect(CandidateId card) implements ChoiceIntent {
    }

    record UnselectUndo(CandidateId card) implements ChoiceIntent {
    }

    record UnselectFinish() implements ChoiceIntent {
    }

    record Cancel() implements ChoiceIntent {
    }

    /* ---------- 区域/形式 ---------- */
    record Place(List<CandidateId> zones) implements ChoiceIntent {
        public Place {
            zones = zones == null ? List.of() : List.copyOf(zones);
        }
    }

    record PositionChoice(Position position) implements ChoiceIntent {
    }

    /* ---------- 宣言族 ---------- */
    record DeclareRace(Set<MonsterRace> races) implements ChoiceIntent {
        public DeclareRace {
            races = races == null ? Set.of() : Set.copyOf(races);
        }
    }

    record DeclareAttribute(Set<Attribute> attributes) implements ChoiceIntent {
        public DeclareAttribute {
            attributes = attributes == null ? Set.of() : Set.copyOf(attributes);
        }
    }

    record DeclareCard(long passcode) implements ChoiceIntent {
    }

    record DeclareNumber(int index) implements ChoiceIntent {
    }

    /* ---------- 排序/指示物 ---------- */
    record Sort(List<CandidateId> order) implements ChoiceIntent {
        public Sort {
            order = order == null ? List.of() : List.copyOf(order);
        }
    }

    record Counter(Map<CandidateId, Integer> removals) implements ChoiceIntent {
        public Counter {
            removals = removals == null ? Map.of() : Map.copyOf(removals);
        }
    }
}
