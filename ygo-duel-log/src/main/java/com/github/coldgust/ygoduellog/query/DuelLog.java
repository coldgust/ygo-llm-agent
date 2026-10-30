package com.github.coldgust.ygoduellog.query;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoduellog.event.DuelEvent;

import java.util.List;

/**
 * 对局记录查询接口（Agent 面向）。
 *
 * <p>典型用法：
 * <ul>
 *   <li>实时跟随：{@link #latestSeq()} + {@link #since(long)}；</li>
 *   <li>局中/局后复盘：{@link #events(EventFilter)}、{@link #turn(int)}、{@link #decisions(String)}；</li>
 *   <li>局面重建：{@link #stateAt(long)}（快照 + 增量；当前版本返回 ≤ seq 的最近全量快照）。</li>
 * </ul>
 */
public interface DuelLog {

    /** 对局元信息。 */
    DuelInfo duelInfo();

    /** 按过滤条件取事件（按 seq 升序）。 */
    List<DuelEvent> events(EventFilter filter);

    /** 某回合的完整事件序列。 */
    List<DuelEvent> turn(int turnNumber);

    /** 某玩家的全部决策（请求与应答配对展平）。 */
    List<DecisionRecord> decisions(String player);

    /**
     * seq 时点的战场局面。当前实现返回 ≤ seq 的最近 {@code STATE_SNAPSHOT}；
     * 尚无快照时返回 null。
     */
    DuelState stateAt(long seq);

    /** 当前最大 seq（实时游标）。 */
    long latestSeq();

    /** 增量拉取：seq 之后（不含）的全部事件。 */
    List<DuelEvent> since(long seq);
}
