package com.github.coldgust.ygoduellog.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.coldgust.ygodomain.duel.DuelState;

import com.github.coldgust.ygoduellog.model.CandidateView;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DuelEventPayload.DuelStarted.class, name = "DUEL_STARTED"),
        @JsonSubTypes.Type(value = DuelEventPayload.DuelEnded.class, name = "DUEL_ENDED"),
        @JsonSubTypes.Type(value = DuelEventPayload.TurnStarted.class, name = "TURN_STARTED"),
        @JsonSubTypes.Type(value = DuelEventPayload.PhaseEntered.class, name = "PHASE_ENTERED"),
        @JsonSubTypes.Type(value = DuelEventPayload.ChainWindowOpened.class, name = "CHAIN_WINDOW_OPENED"),
        @JsonSubTypes.Type(value = DuelEventPayload.ChainWindowClosed.class, name = "CHAIN_WINDOW_CLOSED"),
        @JsonSubTypes.Type(value = DuelEventPayload.ChainResolved.class, name = "CHAIN_RESOLVED"),
        @JsonSubTypes.Type(value = DuelEventPayload.ChainNegated.class, name = "CHAIN_NEGATED"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionRequested.class, name = "DECISION_REQUESTED"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionContext.class, name = "DECISION_CONTEXT"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionMade.class, name = "DECISION_MADE"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionSkipped.class, name = "DECISION_SKIPPED"),
        @JsonSubTypes.Type(value = DuelEventPayload.RetryIssued.class, name = "RETRY_ISSUED"),
        @JsonSubTypes.Type(value = DuelEventPayload.CardMoved.class, name = "CARD_MOVED"),
        @JsonSubTypes.Type(value = DuelEventPayload.LpChanged.class, name = "LP_CHANGED"),
        @JsonSubTypes.Type(value = DuelEventPayload.CounterChanged.class, name = "COUNTER_CHANGED"),
        @JsonSubTypes.Type(value = DuelEventPayload.CardRevealed.class, name = "CARD_REVEALED"),
        @JsonSubTypes.Type(value = DuelEventPayload.EffectActivated.class, name = "EFFECT_ACTIVATED"),
        @JsonSubTypes.Type(value = DuelEventPayload.AttackDeclared.class, name = "ATTACK_DECLARED"),
        @JsonSubTypes.Type(value = DuelEventPayload.StateSnapshot.class, name = "STATE_SNAPSHOT"),
        @JsonSubTypes.Type(value = DuelEventPayload.RawFrame.class, name = "RAW_FRAME"),
})
public sealed interface DuelEventPayload permits
        DuelEventPayload.DuelStarted,
        DuelEventPayload.DuelEnded,
        DuelEventPayload.TurnStarted,
        DuelEventPayload.PhaseEntered,
        DuelEventPayload.ChainWindowOpened,
        DuelEventPayload.ChainWindowClosed,
        DuelEventPayload.ChainResolved,
        DuelEventPayload.ChainNegated,
        DuelEventPayload.DecisionRequested,
        DuelEventPayload.DecisionContext,
        DuelEventPayload.DecisionMade,
        DuelEventPayload.DecisionSkipped,
        DuelEventPayload.RetryIssued,
        DuelEventPayload.CardMoved,
        DuelEventPayload.LpChanged,
        DuelEventPayload.CounterChanged,
        DuelEventPayload.CardRevealed,
        DuelEventPayload.EffectActivated,
        DuelEventPayload.AttackDeclared,
        DuelEventPayload.StateSnapshot,
        DuelEventPayload.RawFrame {


    record PlayerInfo(int seat, String name, int mainDeckSize, int extraDeckSize) {
    }

    record DuelStarted(
            List<PlayerInfo> players,
            int masterRule,
            int startLp,
            int startHand,
            int drawCount,
            int timeLimitSeconds
    ) implements DuelEventPayload {
        public DuelStarted {
            players = players == null ? List.of() : List.copyOf(players);
        }
    }

    /** 对局结束：winner 为 "P0"/"P1"，null = 平局。 */
    record DuelEnded(String winner, String reason, int totalTurns) implements DuelEventPayload {
    }


    /** 回合开始：player 为 "P0"/"P1"。 */
    record TurnStarted(String player, int turnNumber) implements DuelEventPayload {
    }

    /** 进入某阶段（官方阶段枚举名，含战阶各 Step 与伤害步骤）。 */
    record PhaseEntered(String phase) implements DuelEventPayload {
    }

    /** 连锁窗口开启（可发动时点）。 */
    record ChainWindowOpened(int chainLength, long triggeringCard, long triggeringDesc)
            implements DuelEventPayload {
    }

    /** 整条连锁处理完毕（MSG_CHAIN_END）：记录里对应一行"处理结束"。 */
    record ChainWindowClosed(int chainLength) implements DuelEventPayload {
    }

    /** 连锁第 chainIndex 环开始处理（MSG_CHAIN_SOLVING）；card 为该环的卡（可 0 = 未知）。 */
    record ChainResolved(int chainIndex, long card, long desc) implements DuelEventPayload {
    }

    /**
     * 连锁第 chainIndex 环被无效（MSG_CHAIN_NEGATED / MSG_CHAIN_DISABLED）。
     *
     * <p>灰流丽、无限泡影这类"效果无效"就靠它：记录里对应一行"效果无效"，
     * 模型据此知道对方那个效果没结算（用狼/泡影骗灰流丽的判断也依赖这条）。
     */
    record ChainNegated(int chainIndex, long card) implements DuelEventPayload {
    }


    /** 决策可见度：ygopro 协议中对手被问的内容不可见。 */
    enum Visibility {
        /** 完整可见（问自己：候选与约束齐全）。 */
        FULL,
        /** 只有结果可见（问对手：仅能看到其行动造成的状态变化）。 */
        RESULT_ONLY
    }

    record DecisionRequested(
            String player,
            String requestType,
            Visibility visibility,
            int candidateCount,
            String summary
    ) implements DuelEventPayload {
    }

    record DecisionMade(
            long requestSeq,
            String player,
            String requestType,
            String selectionSummary,
            String source,
            long thinkMs,
            int retryCount
    ) implements DuelEventPayload {
    }

    record DecisionSkipped(long requestSeq, String player, String requestType, String reason)
            implements DuelEventPayload {
    }

    /** 服务端打回应答（MSG_RETRY）。 */
    record RetryIssued(String player, int retryCount, String requestType) implements DuelEventPayload {
    }

    record DecisionContext(
            String requestType,
            DecisionRequested.Visibility visibility,
            int candidateCount,
            List<CandidateView> candidates,
            Integer min,
            Integer max,
            boolean cancelable
    ) implements DuelEventPayload {
        public DecisionContext {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public record CandidateView(String kind, long passcode, String zone, long description, boolean directAttack) {
        }
    }


    record CardMoved(
            long passcode,
            String identity,
            String fromZone,
            String fromPosition,
            String toZone,
            String toPosition,
            String reason
    ) implements DuelEventPayload {
    }

    /** LP 变动：delta 为变动量（负 = 受伤/支付），lpAfter 为变动后 LP。 */
    record LpChanged(String player, int delta, int lpAfter, String cause) implements DuelEventPayload {
    }

    record CounterChanged(String card, String counterType, int delta) implements DuelEventPayload {
    }

    /**
     * 一张卡被公开（MSG_CONFIRM_CARDS / MSG_CARD_SELECTED 带来的卡号）。
     *
     * <p>检索就是靠这条才能认出"加入了什么"：进手牌的那条 MSG_MOVE 卡号被服务端抹零，
     * 亮牌消息晚一两帧才到，所以它单列一条事件，渲染层再与"未知进手"合并。
     */
    record CardRevealed(String player, long passcode, String zone) implements DuelEventPayload {
    }

    /**
     * 效果发动（入链）。chainIndex = 这次发动是连锁的第几环；null/0 表示协议未给出。
     * zone = 发动来源区域（{@link DuelLogBridge#zoneOf} 格式；null = 协议未给出）——
     * 多条款卡解不出条款时，靠它 + 全部条款全文让模型判断发动了哪一条。
     */
    record EffectActivated(String player, long passcode, long desc, Integer chainIndex, String zone)
            implements DuelEventPayload {
        public EffectActivated(String player, long passcode, long desc, Integer chainIndex) {
            this(player, passcode, desc, chainIndex, null);
        }
    }

    /** 攻击宣言：target 为 "P0"/"P1" 的怪兽描述，"direct" = 直击。 */
    record AttackDeclared(String player, long attackerPasscode, String target) implements DuelEventPayload {
    }


    record StateSnapshot(DuelState duelState) implements DuelEventPayload {
    }

    record RawFrame(String direction, int msgType, String payloadHex) implements DuelEventPayload {
    }
}
