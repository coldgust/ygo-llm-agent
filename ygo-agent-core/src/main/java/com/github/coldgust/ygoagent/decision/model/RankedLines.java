package com.github.coldgust.ygoagent.decision.model;

import java.util.List;

/** 排序后的候选行 + 置信度。 */
public record RankedLines(List<Line> lines, double confidence, String reason) {

    public RankedLines {
        lines = lines == null ? List.of() : List.copyOf(lines);
        reason = reason == null ? "" : reason;
    }

    public static RankedLines empty(String reason) {
        return new RankedLines(List.of(), 0.0, reason);
    }

    public Line best() {
        return lines.isEmpty() ? null : lines.getFirst();
    }
}
