package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.knowledge.RecordedComboSource;

public record DuelWebHooks(DecisionTraceWriter extraTraceWriter,
                           ComboRecordSink comboRecordSink,
                           RecordedComboSource recordedCombos,
                           DuelEventListener listener) {

    public interface DuelEventListener {
        void onEvent(String duelId, String eventName, Object payload);
    }

    public static DuelWebHooks none() {
        return new DuelWebHooks(null, null, null, null);
    }
}
