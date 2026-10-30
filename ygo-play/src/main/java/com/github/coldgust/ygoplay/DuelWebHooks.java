package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.planexec.llm.RecordedComboSource;

/**
 * Web 层注入的旁路钩子（全部可空）。
 *
 * @param extraTraceWriter 附加轨迹写入（事件总线扇出；JSONL 文件写入始终保留）
 * @param comboRecordSink  combo 录入落库回调（录入模式对局结束后触发）
 * @param recordedCombos   实战录入数据源（PG）；find_combos 在种子路线之外合并展示
 * @param listener         对局事件监听（duelId 由 runner 从 trace/duel-log 文件名推导）
 */
public record DuelWebHooks(DecisionTraceWriter extraTraceWriter,
                           ComboRecordSink comboRecordSink,
                           RecordedComboSource recordedCombos,
                           DuelEventListener listener) {

    /** 对局事件监听：duelId 由 DuelRunner 从 duel-log 文件名推导。 */
    public interface DuelEventListener {
        void onEvent(String duelId, String eventName, Object payload);
    }

    public static DuelWebHooks none() {
        return new DuelWebHooks(null, null, null, null);
    }
}
