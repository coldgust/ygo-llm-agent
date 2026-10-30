package com.github.coldgust.ygoduellog.model;

/** 候选快照（来自 DECISION_CONTEXT 事件）。 */
public record CandidateView(String kind, long passcode, String zone, long description) {
}
