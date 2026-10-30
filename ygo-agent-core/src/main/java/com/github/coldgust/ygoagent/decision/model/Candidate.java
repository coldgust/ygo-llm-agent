package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneRef;

/**
 * 候选统一视图：payload 持有请求里的**原始对象引用**，
 * SelectionMapper 映射时按引用回指，杜绝下标/相等性错位。
 */
public sealed interface Candidate {

    CandidateId id();

    record CardC(CandidateId id, ChoiceRequest.CardChoice value) implements Candidate {
    }

    record EffectC(CandidateId id, ChoiceRequest.EffectChoice value) implements Candidate {
    }

    record AttackC(CandidateId id, ChoiceRequest.AttackChoice value) implements Candidate {
    }

    record TributeC(CandidateId id, ChoiceRequest.TributeChoice value) implements Candidate {
    }

    record ZoneC(CandidateId id, ZoneRef value) implements Candidate {
    }

    record CounterC(CandidateId id, ChoiceRequest.CounterRequest.CounterChoice value) implements Candidate {
    }

    /**
     * 阶段级动作候选（进战阶 / 结束回合 / 进 M2 / 结束战斗 / 洗手牌）。
     *
     * <p>以前这些只渲染成"可选：结束回合"这类**没有编号**的文字，模型想选就没有 id 可填，
     * 于是拿 C0 硬凑（C0 只在连锁/是否发动里存在），被判"选择越界"后整条决策降级成经验规则——
     * 实机就是这样把灰流丽通召上场、还连盖两张泡影的。给它们真编号，模型才能表达"结束回合"。
     */
    record FlagC(CandidateId id, Flag flag) implements Candidate {
    }

    /**
     * 弃权类动作（不响应 / 不发动 / 否 / 取消 / 完成选择）。
     *
     * <p>以前这些是**没有编号的伪候选 C0**：它不在 {@code candidates()} 里，编码层只能特判，
     * 一旦模型在"本请求没有 C0"的决策点填了 C0（例如主阶段想结束回合），就被判越界、
     * 整条决策降级成经验规则。现在它们是真候选，有真编号，"choice 必须能在候选集里查到"这条不变式成立。
     */
    record DeclineC(CandidateId id, Decline kind) implements Candidate {
    }

    /** 弃权类动作的种类 → 语义意图。 */
    enum Decline {
        CHAIN_DECLINE("不响应（弃权）"),
        EFFECT_NO("不发动"),
        YES_NO_NO("否"),
        CANCEL("取消上一个动作"),
        UNSELECT_FINISH("完成选择");

        private final String label;

        Decline(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 阶段级动作。 */
    enum Flag {
        ENTER_BATTLE("进战阶"),
        END_TURN("结束回合"),
        SHUFFLE_HAND("洗手牌"),
        ENTER_MAIN2("进 M2"),
        END_BATTLE("结束战斗");

        private final String label;

        Flag(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
