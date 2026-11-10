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
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionRequested.class, name = "DECISION_REQUESTED"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionContext.class, name = "DECISION_CONTEXT"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionMade.class, name = "DECISION_MADE"),
        @JsonSubTypes.Type(value = DuelEventPayload.DecisionSkipped.class, name = "DECISION_SKIPPED"),
        @JsonSubTypes.Type(value = DuelEventPayload.RetryIssued.class, name = "RETRY_ISSUED"),
        @JsonSubTypes.Type(value = DuelEventPayload.CardMoved.class, name = "CARD_MOVED"),
        @JsonSubTypes.Type(value = DuelEventPayload.LpChanged.class, name = "LP_CHANGED"),
        @JsonSubTypes.Type(value = DuelEventPayload.CounterChanged.class, name = "COUNTER_CHANGED"),
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
        DuelEventPayload.DecisionRequested,
        DuelEventPayload.DecisionContext,
        DuelEventPayload.DecisionMade,
        DuelEventPayload.DecisionSkipped,
        DuelEventPayload.RetryIssued,
        DuelEventPayload.CardMoved,
        DuelEventPayload.LpChanged,
        DuelEventPayload.CounterChanged,
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

    /** 连锁封口，开始逆序结算。 */
    record ChainWindowClosed(int chainLength) implements DuelEventPayload {
    }

    record ChainResolved(int chainIndex, long card, long desc) implements DuelEventPayload {
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

    /** 效果发动（入链）。 */
    record EffectActivated(String player, long passcode, long desc) implements DuelEventPayload {
    }

    /** 攻击宣言：target 为 "P0"/"P1" 的怪兽描述，"direct" = 直击。 */
    record AttackDeclared(String player, long attackerPasscode, String target) implements DuelEventPayload {
    }


    record StateSnapshot(DuelState duelState) implements DuelEventPayload {
    }

    record RawFrame(String direction, int msgType, String payloadHex) implements DuelEventPayload {
    }
}
