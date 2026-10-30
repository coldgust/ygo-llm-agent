package com.github.coldgust.ygoagent.decision.model;

/** 决策各阶段耗时（纳秒）。 */
public record Timing(long reflexNanos, long tacticalNanos, long totalNanos) {

    public static final Timing ZERO = new Timing(0, 0, 0);
}
