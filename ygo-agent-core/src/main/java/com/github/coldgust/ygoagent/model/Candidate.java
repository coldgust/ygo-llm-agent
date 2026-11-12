package com.github.coldgust.ygoagent.model;

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

    record FlagC(CandidateId id, Flag flag) implements Candidate {
    }

    record DeclineC(CandidateId id, Decline kind) implements Candidate {
    }

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
