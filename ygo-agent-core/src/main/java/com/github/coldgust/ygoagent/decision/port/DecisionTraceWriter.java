package com.github.coldgust.ygoagent.decision.port;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;

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
}
