package com.github.coldgust.ygoagent.trace;

import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;

import java.util.List;
import java.util.Map;

/** 把同一份轨迹扇出到多个写入器（JSONL 文件 + Web 事件总线）。任一失败不影响其余。 */
public final class CompositeTraceWriter implements DecisionTraceWriter {

    private final List<DecisionTraceWriter> writers;

    public CompositeTraceWriter(List<DecisionTraceWriter> writers) {
        this.writers = List.copyOf(writers);
    }

    @Override
    public void append(DecisionPoint point, DuelDecision decision) {
        for (DecisionTraceWriter writer : writers) {
            try {
                writer.append(point, decision);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void event(String kind, Map<String, Object> payload) {
        for (DecisionTraceWriter writer : writers) {
            try {
                writer.event(kind, payload);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
