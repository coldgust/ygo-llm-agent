package com.github.coldgust.ygoagent.model;

/** 决策点内唯一的候选标识（不跨决策点复用）。 */
public record CandidateId(String value) {
    public static CandidateId of(int index) {
        return new CandidateId("C" + index);
    }
}
