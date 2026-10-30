package com.github.coldgust.ygoagent.decision.trace;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTrace;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把决策轨迹事件同时打到日志（可关）。
 *
 * <p>和 JSONL 轨迹的区别是"人能直接读、和 LLM 请求在同一个时间线上"：
 * 打开 {@code YGO_VERBOSE_LOG} 后，日志里会按发生顺序出现
 * {@code [LLM→]} → {@code [TOOL→]} → {@code [决策]} → {@code [事件]}，
 * 不用再开一个 jsonl 文件对照时间戳。
 *
 * <p>只记"旁路事件"（计划发布/放弃、位置线索、最终提交、收尾统计）。
 * 每次决策的级别与未命中原因由 {@code PlanExecutorDecisionPort} 直接记，
 * 这里再记一遍会重复。
 */
public final class TraceLogWriter implements DecisionTraceWriter {

    private static final Logger log =
            LoggerFactory.getLogger("ygo.decision");

    /** 这些事件对"看流程"没有增量，跳过。 */
    private static final Set<String> SKIP = Set.of(
            "DECISION", "EXEC_DECISION", "PLAN_EVENT_RAW");

    @Override
    public void append(DecisionPoint point, DuelDecision decision) {
        // 决策摘要由执行器记（含未命中原因），这里不重复
    }

    @Override
    public void event(String kind, Map<String, Object> payload) {
        if (!LlmTrace.enabled() || kind == null || SKIP.contains(kind)) {
            return;
        }
        log.info("[事件] {}{}", kind, payload == null || payload.isEmpty() ? "" : " " + format(payload));
    }

    /**
     * 事件载荷转成一行可读的 key=value。
     *
     * <p>刻意不用 JSON：日志要能一眼扫过去。值过长时截断，避免一步把整份计划刷屏。
     */
    static String format(Map<String, Object> payload) {
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

    private static String readable(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> collection) {
            return "[" + collection.size() + " 项]";
        }
        return LlmTrace.preview(String.valueOf(value), 120);
    }

    private TraceLogWriter() {
    }

    /** 单例：无状态，装配处直接用。 */
    public static final TraceLogWriter INSTANCE = new TraceLogWriter();
}
