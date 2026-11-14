package com.github.coldgust.ygoagent.trace;

import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TraceLogWriter implements DecisionTraceWriter {

    private static final Logger log =
            LoggerFactory.getLogger("ygo.decision");

    private final VerboseLog trace;

    public TraceLogWriter(VerboseLog trace) {
        this.trace = VerboseLog.offOr(trace);
    }

    /** 这些事件对"看流程"没有增量，跳过。 */
    private static final Set<String> SKIP = Set.of(
            "DECISION", "EXEC_DECISION", "PLAN_EVENT_RAW");

    @Override
    public void append(DecisionPoint point, DuelDecision decision) {
        // 决策摘要由执行器记（含未命中原因），这里不重复
    }

    @Override
    public void event(String kind, Map<String, Object> payload) {
        if (!trace.enabled() || kind == null || SKIP.contains(kind)) {
            return;
        }
        log.info("[事件] {}{}", kind, payload == null || payload.isEmpty() ? "" : " " + format(payload));
    }

    /**
     * <p>刻意不用 JSON：日志要能一眼扫过去。值过长时截断，避免一步把整份计划刷屏。
     */
    String format(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(payload).entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(readable(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private String readable(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> collection) {
            return "[" + collection.size() + " 项]";
        }
        return trace.preview(String.valueOf(value), 120);
    }

}
