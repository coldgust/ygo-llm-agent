package com.github.coldgust.ygoagent.trace;

import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;

import java.util.Map;

/** 决策轨迹写入（异步/有界；实现不得向调用方抛异常）。 */
public interface DecisionTraceWriter {

    void append(DecisionPoint point, DuelDecision decision);

    default void event(String kind, Map<String, Object> payload) {
    }

    DecisionTraceWriter NOOP = new DecisionTraceWriter() {
        @Override
        public void append(DecisionPoint point, DuelDecision decision) {
        }

        @Override
        public void event(String kind, Map<String, Object> payload) {
        }
    };

    /** null → NOOP：可选轨迹落点的统一默认化（各组装点共用，替代三元样板）。 */
    static DecisionTraceWriter noopOr(DecisionTraceWriter writer) {
        return writer == null ? NOOP : writer;
    }
}
