package com.github.coldgust.ygoagent.planner;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件检查点的调度状态（模块内部协作类型）。
 *
 * <p>只拥有调度相关的计数与锁：本回合已发数、在途锁（防重复排队）、记录水位（增量拉取）、
 * 回合预算计数。判断逻辑（配额、自排空条件）集中在这里；编排（收事件、拼批、调 LLM）
 * 留在 {@link PlannerSession}。
 */
final class EventCheckpoints {

    /** 两次事件检查点之间的最小间隔：避免"深度档"连续猛跑占住 LLM 配额与 worker 线程。 */
    static final long GAP_MS = 1_500;

    /** 本回合已发的 events checkpoint 数（含在途）。 */
    private final AtomicLong sentThisTurn = new AtomicLong();
    /** 是否已有检查点在排队/执行：有则不再排新任务，事件攒到下一批一起发。 */
    private final AtomicBoolean queued = new AtomicBoolean();
    /** 回合预算：本回合"为等规划器而推迟应答"的累计毫秒（waitForPlan 与 urgent 路径累计）。 */
    private final AtomicLong turnBudgetUsedMs = new AtomicLong();
    /**
     * 事件批已拉取到的记录 seq（回合内）。只在 planner 单线程（worker）上读写。
     * 回合切换时对齐到锚点构建时刻，避免"锚点记录里已有 + 首个事件批再发一遍"的重复。
     */
    private long watermark;

    /** 尝试占用在途锁（false = 已有在途检查点，事件攒到下一批）。 */
    boolean tryQueue() {
        return queued.compareAndSet(false, true);
    }

    void release() {
        queued.set(false);
    }

    boolean quotaAvailable(int eventsQuota, long totalCheckpoints, int totalQuota) {
        return sentThisTurn.get() < eventsQuota && totalCheckpoints < totalQuota;
    }

    void incrementSent() {
        sentThisTurn.incrementAndGet();
    }

    boolean shouldSelfDrain(boolean morePending, int eventsQuota,
                            long totalCheckpoints, int totalQuota) {
        return morePending && sentThisTurn.get() < eventsQuota
                && totalCheckpoints < totalQuota;
    }

    void resetForTurn() {
        sentThisTurn.set(0);
        watermark = 0;
    }

    long watermark() {
        return watermark;
    }

    void markWatermark(long seq) {
        watermark = seq;
    }

    void addTurnBudget(long millis) {
        turnBudgetUsedMs.addAndGet(millis);
    }

    long turnBudgetUsedMs() {
        return turnBudgetUsedMs.get();
    }

    void resetTurnBudget() {
        turnBudgetUsedMs.set(0);
    }
}
