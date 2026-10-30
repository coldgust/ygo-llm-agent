package com.github.coldgust.ygoagent.decision.model;

import java.time.Duration;

/**
 * 决策预算。所有下游调用都取 {@link #remainingMillis()} 设超时；
 * Deep 路径必须早于 {@link #deepCutoffNanos()} 返回。
 *
 * @param startNanos    开始时刻（System.nanoTime）
 * @param hardNanos     硬截止时刻
 * @param safetyMillis  安全边际（编码/发送/GC）
 */
public record DecisionDeadline(long startNanos, long hardNanos, long safetyMillis) {

    public static DecisionDeadline of(Duration budget) {
        long now = System.nanoTime();
        return new DecisionDeadline(now, now + budget.toNanos(), 200);
    }

    public static DecisionDeadline of(long budgetMillis) {
        return of(Duration.ofMillis(budgetMillis));
    }

    public long remainingMillis() {
        return Math.max(0, (hardNanos - System.nanoTime()) / 1_000_000);
    }

    public boolean expired() {
        return System.nanoTime() >= hardNanos;
    }

    public long deepCutoffNanos() {
        return hardNanos - safetyMillis * 1_000_000L;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
