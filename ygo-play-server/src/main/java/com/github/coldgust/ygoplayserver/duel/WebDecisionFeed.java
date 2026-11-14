package com.github.coldgust.ygoplayserver.duel;

import java.util.Map;

/**
 * 前端决策流的可见性过滤（2026-11-14 用户指令）：<b>① 唯一合法不上前端</b>。
 *
 * <p>① 唯一合法 = 没有判断余地的机械应答（引擎只给一个选项、空链确认、单一确认、选位置…）。
 * 实机一局 30 手决策里有 12 手是这种行（全是一句「不连锁（本时点连锁为空，放弃发动）」），
 * 信息量为零，却把真正有判断的决策淹没在事件流里。
 *
 * <p><b>只影响前端 SSE</b>：轨迹文件（duel-*.jsonl）、服务端日志的 `[决策]` 行、复盘素材一律照旧，
 * 离线排查与回放要一条不少。所以过滤放在 web 出口（{@link DuelRunnerFactory} 的 publish 回调），
 * 不放在流水或 runner 里。
 */
public final class WebDecisionFeed {

    private WebDecisionFeed() {
    }

    /** 该事件是否要推给前端。不带等级的事件（协议兜底、状态类）一律保留。 */
    public static boolean visible(Map<String, Object> event) {
        if (event == null) {
            return true;
        }
        // EXEC_DECISION/SUBMITTED 用 level；DECISION（轨迹原文）用 pathKind
        Object level = event.get("level");
        if (level == null) {
            level = event.get("pathKind");
        }
        return level == null || !"ONLY_CHOICE".equals(String.valueOf(level));
    }
}
