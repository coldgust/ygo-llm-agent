package com.github.coldgust.ygoagent.trace;

import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>消费者不得抛异常（实现内部自行兜底）；本类也不抛。
 */
public final class CallbackTraceWriter implements DecisionTraceWriter {

    private final Consumer<Map<String, Object>> sink;

    public CallbackTraceWriter(Consumer<Map<String, Object>> sink) {
        this.sink = sink;
    }

    @Override
    public void append(DecisionPoint point, DuelDecision decision) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "DECISION");
        event.put("seq", point.seq());
        event.put("turn", point.turn());
        event.put("phase", String.valueOf(point.phase()));
        event.put("requestKind", point.kind().name());
        event.put("pathKind", decision.pathKind().name());
        event.put("pathLabel", decision.pathKind().label());
        event.put("intent", decision.intent().getClass().getSimpleName());
        event.put("intentLabel", ChoiceIntent.label(decision.intent()));
        event.put("confidence", decision.confidence());
        event.put("explanation", decision.explanation() == null ? ""
                : decision.explanation().conclusion() == null ? "" : decision.explanation().conclusion());
        emit(event);
    }

    @Override
    public void event(String kind, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        event.put("kind", kind);
        emit(event);
    }

    private void emit(Map<String, Object> event) {
        try {
            sink.accept(event);
        } catch (RuntimeException ignored) {
        }
    }
}
