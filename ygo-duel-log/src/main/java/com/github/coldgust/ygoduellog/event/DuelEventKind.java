package com.github.coldgust.ygoduellog.event;

/**
 * 对局事件种类。与 {@link DuelEventPayload} 的子类型一一对应。
 */
public enum DuelEventKind {
    /* ---------- 生命周期 ---------- */
    DUEL_STARTED,
    DUEL_ENDED,

    /* ---------- 时点切换 ---------- */
    TURN_STARTED,
    PHASE_ENTERED,
    CHAIN_WINDOW_OPENED,
    CHAIN_WINDOW_CLOSED,
    CHAIN_RESOLVED,

    /* ---------- 决策 ---------- */
    DECISION_REQUESTED,
    DECISION_CONTEXT,
    DECISION_MADE,
    DECISION_SKIPPED,
    RETRY_ISSUED,

    /* ---------- 状态变化 ---------- */
    CARD_MOVED,
    LP_CHANGED,
    COUNTER_CHANGED,
    EFFECT_ACTIVATED,
    ATTACK_DECLARED,

    /* ---------- 快照与审计 ---------- */
    STATE_SNAPSHOT,
    RAW_FRAME
}
