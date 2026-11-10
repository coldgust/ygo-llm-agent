package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.planexec.llm.RecordedComboSource;

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
