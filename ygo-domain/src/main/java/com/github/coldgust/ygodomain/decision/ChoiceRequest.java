package com.github.coldgust.ygodomain.decision;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Set;

/**
 * "下一步动作集合"：服务端枚举好并下发的候选集合（请求）。
 *
 * <p>合法性由构造保证——只要决策输出对应的 {@link ChoiceSelection}
 * 从本请求的候选中取值，动作即不可能非法。
 * SDK 已过滤"问对手"的请求并完成计时握手，此处看到的全部是问自己的。
 *
 * <p>每个请求记录候选与约束（min/max/强制/可否取消）；
 * 应答用同包的 {@link ChoiceSelection}。
 */
public sealed interface ChoiceRequest {

    /** 请求是否允许取消（int32 -1）。具体取值见各实现。 */
    boolean cancelable();

    /** 一张可选的卡（解码自协议候选：code + 打包位置）。 */
    record CardChoice(long instanceId, CardIdentity identity, ZoneRef zone) {
    }

    /** 一个可发动的效果（附带效果描述索引，供知识模块检索效果文本）。 */
    record EffectChoice(CardChoice card, long description) {
    }

    /** 一只可攻击的怪兽（directAttack = 可直接攻击对方玩家）。 */
    record AttackChoice(CardChoice card, boolean directAttack) {
    }

    /** 一只可作祭品的怪兽（releaseCount = 该卡可提供的祭品数，通常 1）。 */
    record TributeChoice(CardChoice card, int releaseCount) {
    }


    /**
     * Main Phase 的动作集合：六个候选组（通常召唤/特殊召唤/改变表示形式/
     * 盖放怪兽/盖放魔法陷阱/发动效果）+ 进战阶 / 结束回合 / 洗手牌三个标志。
     */
    record MainPhaseRequest(
            List<CardChoice> normalSummonable,
            List<CardChoice> specialSummonable,
            List<CardChoice> repositionable,
            List<CardChoice> monsterSettable,
            List<CardChoice> spellTrapSettable,
            List<EffectChoice> activatable,
            boolean canEnterBattlePhase,
            boolean canEndTurn,
            boolean canShuffleHand
    ) implements ChoiceRequest {
        public MainPhaseRequest {
            normalSummonable = safe(normalSummonable);
            specialSummonable = safe(specialSummonable);
            repositionable = safe(repositionable);
            monsterSettable = safe(monsterSettable);
            spellTrapSettable = safe(spellTrapSettable);
            activatable = safe(activatable);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }


    /** Battle Phase 的动作集合：可发动的效果、可攻击的怪兽、进 Main Phase 2 / 结束战斗阶段。 */
    record BattlePhaseRequest(
            List<EffectChoice> activatable,
            List<AttackChoice> attackable,
            boolean canEnterMainPhase2,
            boolean canEndBattlePhase
    ) implements ChoiceRequest {
        public BattlePhaseRequest {
            activatable = safe(activatable);
            attackable = safe(attackable);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }


    /** 选卡：必须选 [min, max] 张，下标不重复。 */
    record CardSelectRequest(int min, int max, boolean cancelable, List<CardChoice> candidates)
            implements ChoiceRequest {
        public CardSelectRequest {
            candidates = safe(candidates);
        }
    }

    /** 选祭品（Tribute）：被选卡的 releaseCount 之和须满足上级召唤要求。 */
    record TributeSelectRequest(int min, int max, boolean cancelable, List<TributeChoice> candidates)
            implements ChoiceRequest {
        public TributeSelectRequest {
            candidates = safe(candidates);
        }
    }

    /**
     * 按数值和选卡（Select Sum）：候选分"必选"与"可选"两组。
     * mode：0 = 有上限（总等级须落在 [min,max] 且凑足 acc），1 = 无上限（至少凑足 acc）。
     * 必选卡必须按其顺序出现在应答前段。
     */
    record SumRequest(
            boolean unboundedUpper,
            int targetSum,
            int min,
            int max,
            List<CardChoice> mustSelect,
            List<CardChoice> optional
    ) implements ChoiceRequest {
        public SumRequest {
            mustSelect = safe(mustSelect);
            optional = safe(optional);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /**
     * 选卡 / 取消已选（可反复）：每次回答一个动作，直到 finish 或 cancel。
     * index 语义已在 SDK 编码层处理，决策层直接引用卡。
     */
    record UnselectRequest(
            boolean finishable,
            boolean cancelable,
            int min,
            int max,
            List<CardChoice> selectable,
            List<CardChoice> selected
    ) implements ChoiceRequest {
        public UnselectRequest {
            selectable = safe(selectable);
            selected = safe(selected);
        }
    }

    /** 连锁：forced = true 的效果必须发动；decline 仅在无强制效果时合法。 */
    record ChainRequest(List<ChainOption> options) implements ChoiceRequest {
        public ChainRequest {
            options = safe(options);
        }

        @Override
        public boolean cancelable() {
            return options.stream().noneMatch(ChainOption::forced);
        }

        public record ChainOption(EffectChoice effect, boolean forced) {
        }

        public boolean hasForcedEffect() {
            return options.stream().anyMatch(ChainOption::forced);
        }
    }


    /** 选区域（须选 count 个；availableZones 已是"不可用掩码取补集"后的合法区域）。 */
    record PlaceRequest(int count, List<ZoneRef> availableZones) implements ChoiceRequest {
        public PlaceRequest {
            availableZones = safe(availableZones);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /** 选要禁用的区域（语义与 PlaceRequest 相反：从 availableZones 中选 count 个作为禁用区）。 */
    record DisfieldRequest(int count, List<ZoneRef> availableZones) implements ChoiceRequest {
        public DisfieldRequest {
            availableZones = safe(availableZones);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /** 选表示形式：positions 为该卡允许的表示形式集合。 */
    record PositionRequest(CardChoice card, Set<Position> positions) implements ChoiceRequest {
        public PositionRequest {
            positions = positions == null ? Set.of() : Set.copyOf(positions);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }


    /** 效果是否发动（附发动卡与效果描述）。 */
    record EffectYesNoRequest(CardChoice card, long description) implements ChoiceRequest {
        @Override
        public boolean cancelable() {
            return true; // "否"即放弃发动
        }
    }

    record YesNoRequest(long description) implements ChoiceRequest {
        @Override
        public boolean cancelable() {
            return true; // "否"
        }
    }

    /** 多选一：candidates 为效果分支描述索引，回答选中的分支下标。 */
    record OptionRequest(List<Long> optionDescriptions) implements ChoiceRequest {
        public OptionRequest {
            optionDescriptions = safe(optionDescriptions);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }


    /** 宣言种族：必须恰好宣言 count 个 available 中的种族。 */
    record DeclareRaceRequest(int count, Set<MonsterRace> available) implements ChoiceRequest {
        public DeclareRaceRequest {
            available = available == null ? Set.of() : Set.copyOf(available);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /** 宣言属性：必须恰好宣言 count 个 available 中的属性。 */
    record DeclareAttributeRequest(int count, Set<Attribute> available)
            implements ChoiceRequest {
        public DeclareAttributeRequest {
            available = available == null ? Set.of() : Set.copyOf(available);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /**
     * 宣言卡名：必须回一个满足服务器条件的合法卡号。
     * <p>{@code opcodes} 是协议下发的逆波兰（RPN）条件表达式（不是候选卡列表！）：
     * 例如 "非额外卡组怪兽" 会下发 [类型掩码, OPCODE_ISTYPE, OPCODE_NOT]。
     * 空列表表示无限制，可自由宣言任意存在的卡。
     */
    record DeclareCardRequest(List<Long> opcodes) implements ChoiceRequest {
        public DeclareCardRequest {
            opcodes = safe(opcodes);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /** 宣言数字：回答候选中的下标。 */
    record DeclareNumberRequest(List<Integer> numbers) implements ChoiceRequest {
        public DeclareNumberRequest {
            numbers = safe(numbers);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }


    /** 卡序排列：对新顺序提交一个排列。 */
    record SortRequest(List<CardChoice> cards) implements ChoiceRequest {
        public SortRequest {
            cards = safe(cards);
        }

        @Override
        public boolean cancelable() {
            return false;
        }
    }

    /** 指示物移除：总数必须恰好为 total，每张不超过其现有量。 */
    record CounterRequest(long counterType, int total, List<CounterChoice> candidates)
            implements ChoiceRequest {
        public CounterRequest {
            candidates = safe(candidates);
        }

        @Override
        public boolean cancelable() {
            return false;
        }

        /** 一张带指示物的候选卡（available = 该卡现有指示物数）。 */
        public record CounterChoice(CardChoice card, int available) {
        }
    }


    static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
