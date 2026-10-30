package com.github.coldgust.ygoduellog.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.coldgust.ygodomain.duel.DuelState;

import com.github.coldgust.ygoduellog.model.CandidateView;
import java.util.List;

/**
 * 对局事件的载荷（与 {@link DuelEventKind} 一一对应）。
 *
 * <p>全部为不可变 record；JSON 序列化时以 {@code type} 字段区分子类型，
 * 便于事后从 JSONL 反序列化回放。
 */
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

    /* ---------- 生命周期 ---------- */

    /** 一名玩家的开局信息。 */
    record PlayerInfo(int seat, String name, int mainDeckSize, int extraDeckSize) {
    }

    /**
     * 对局开始。
     *
     * @param players          双方信息（seat 为绝对座位）
     * @param masterRule       大师规则版本
     * @param startLp          初始 LP
     * @param startHand        初始手牌数
     * @param drawCount        每回合抽卡数
     * @param timeLimitSeconds 每回合计时（0 = 不限时）
     */
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

    /* ---------- 时点切换 ---------- */

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

    /** 单环结算完成。 */
    record ChainResolved(int chainIndex, long card, long desc) implements DuelEventPayload {
    }

    /* ---------- 决策 ---------- */

    /** 决策可见度：ygopro 协议中对手被问的内容不可见。 */
    enum Visibility {
        /** 完整可见（问自己：候选与约束齐全）。 */
        FULL,
        /** 只有结果可见（问对手：仅能看到其行动造成的状态变化）。 */
        RESULT_ONLY
    }

    /**
     * 被问（决策请求）。
     *
     * @param player         "P0"/"P1"
     * @param requestType    请求类型（如 MainPhaseRequest）
     * @param visibility     可见度
     * @param candidateCount 候选动作数
     * @param summary        人类可读摘要（供 LLM 直接阅读）
     */
    record DecisionRequested(
            String player,
            String requestType,
            Visibility visibility,
            int candidateCount,
            String summary
    ) implements DuelEventPayload {
    }

    /**
     * 已作答。
     *
     * @param requestSeq       对应 DECISION_REQUESTED 事件的 seq（配对锚点）
     * @param player           "P0"/"P1"
     * @param requestType      请求类型
     * @param selectionSummary 决策摘要（人类可读）
     * @param source           来源：PLAYER=决策者主动 / FALLBACK=重试保底 / TIMEOUT_FALLBACK=超时兜底
     * @param thinkMs          思考耗时（请求到达 → 应答发出）
     * @param retryCount       提交前累计的 RETRY 次数
     */
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

    /**
     * 未作答（超时被跳过 / SDK 自动跳过且未发出应答）。
     *
     * @param requestSeq  对应 DECISION_REQUESTED 事件的 seq
     * @param player      "P0"/"P1"
     * @param requestType 请求类型
     * @param reason      原因（TIMEOUT / AUTO_DECLINE_CHAIN / …）
     */
    record DecisionSkipped(long requestSeq, String player, String requestType, String reason)
            implements DuelEventPayload {
    }

    /** 服务端打回应答（MSG_RETRY）。 */
    record RetryIssued(String player, int retryCount, String requestType) implements DuelEventPayload {
    }

    /**
     * 决策候选集快照：回放/评测重建决策点所需（体积较大，默认关闭，仅调试输出）。
     */
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

        /** 单个候选的语义摘要（不含协议细节）。 */
        public record CandidateView(String kind, long passcode, String zone, long description, boolean directAttack) {
        }
    }

    /* ---------- 状态变化 ---------- */

    /**
     * 万能卡移动（抽卡/召唤/盖放/送墓/除外/回卡组/翻转/表示形式变更统一表达）。
     * from 或 to 为 null 表示该端不可见（如从卡组抽出）或事件不适用。
     *
     * @param passcode 卡的 passcode（未知卡为 0）
     * @param identity 卡名描述（未知卡为 "unknown"）
     */
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

    /** 指示物变动。 */
    record CounterChanged(String card, String counterType, int delta) implements DuelEventPayload {
    }

    /** 效果发动（入链）。 */
    record EffectActivated(String player, long passcode, long desc) implements DuelEventPayload {
    }

    /** 攻击宣言：target 为 "P0"/"P1" 的怪兽描述，"direct" = 直击。 */
    record AttackDeclared(String player, long attackerPasscode, String target) implements DuelEventPayload {
    }

    /* ---------- 快照与审计 ---------- */

    /** 战场镜像全量快照（不可变 DuelState）。 */
    record StateSnapshot(DuelState duelState) implements DuelEventPayload {
    }

    /** 原始协议帧（可选审计层）。 */
    record RawFrame(String direction, int msgType, String payloadHex) implements DuelEventPayload {
    }
}
