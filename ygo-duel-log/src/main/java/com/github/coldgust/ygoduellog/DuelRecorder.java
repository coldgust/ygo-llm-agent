package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;

import java.util.function.Consumer;

/**
 * 对局事件记录入口（生产者面向的 API）。
 *
 * <p>实现必须保证：任意线程调用 {@link #append} 都不阻塞对局；
 * 事件 seq 在实现内单调递增且落盘顺序与 seq 一致。
 *
 * <p>生产者通过 {@link #updateContext} 维护"当前回合/阶段/回合起始时刻"，
 * 记录器据此填充每条事件的公共头。
 */
public interface DuelRecorder {

    /**
     * 记录一条事件（actor 为事件主体："P0"/"P1"/"SYSTEM"）。
     *
     * @return 事件 seq（调用方同步获得，用于请求-应答配对）；记录已关闭时返回 -1
     */
    long append(String actor, DuelEventKind kind, DuelEventPayload payload);

    /**
     * 更新公共头上下文（回合数 / 阶段 / 回合起始毫秒时刻，用于计算 gameClockMs）。
     */
    void updateContext(int turn, String phase, long turnStartWallMillis);

    /** 实时订阅（对局进行中即可收到事件；回调运行在写者线程，须快速返回）。 */
    void subscribe(Consumer<DuelEvent> listener);

    /** 附加落盘 Sink（可多个）。 */
    void attachSink(DuelEventSink sink);

    /** 结束记录（等待队列排空并关闭 Sink）。 */
    void close();
}
