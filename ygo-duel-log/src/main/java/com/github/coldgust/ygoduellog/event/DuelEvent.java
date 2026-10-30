package com.github.coldgust.ygoduellog.event;

import java.time.Instant;

/**
 * 一条对局事件。
 *
 * @param seq        单调递增序号（记录器内唯一；语义层与原始层的对齐锚点）
 * @param wallClock  真实时间
 * @param gameClockMs 本回合已用毫秒（对照服务端回合计时）
 * @param turn       回合数（0 = 对局尚未开始）
 * @param phase      当前阶段（官方阶段枚举名）
 * @param actor      事件主体："P0" / "P1" / "SYSTEM"
 * @param kind       事件种类
 * @param payload    载荷
 */
public record DuelEvent(
        long seq,
        Instant wallClock,
        long gameClockMs,
        int turn,
        String phase,
        String actor,
        DuelEventKind kind,
        DuelEventPayload payload
) {
}
