package com.github.coldgust.ygoagent.model;

import java.util.List;

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
