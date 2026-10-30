package com.github.coldgust.ygoduellog.replay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JSONL 对局记录中重建"决策点"序列：请求 + 候选集 + 应答。
 * <p>只解析决策相关事件，不反序列化完整 DuelState，避免领域模型版本耦合。
 */
public final class DecisionReplayReader {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    /** 一个可回放的决策点。 */
    public record ReplayDecision(
            long requestSeq,
            int turn,
            String requestType,
            int candidateCount,
            Integer min,
            Integer max,
            String chosen,
            String source,
            long thinkMs,
            boolean answered
    ) {
    }

    public static List<ReplayDecision> read(Path file) throws IOException {
        return read(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static List<ReplayDecision> read(List<String> lines) {
        Map<Long, Mutable> bySeq = new LinkedHashMap<>();
        List<Mutable> ordered = new ArrayList<>();

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(line);
            } catch (RuntimeException e) {
                continue;
            }
            String kind = root.path("kind").asText("");
            long seq = root.path("seq").asLong(-1);
            int turn = root.path("turn").asInt(0);
            JsonNode payload = root.path("payload");
            switch (kind) {
                case "DECISION_REQUESTED" -> {
                    Mutable m = new Mutable(seq, turn,
                            payload.path("requestType").asText(""),
                            payload.path("candidateCount").asInt(0));
                    bySeq.put(seq, m);
                    ordered.add(m);
                }
                case "DECISION_CONTEXT" -> {
                    Mutable m = lastWithoutContext(ordered);
                    if (m != null) {
                        m.candidateCount = payload.path("candidateCount").asInt(m.candidateCount);
                        m.min = payload.hasNonNull("min") ? payload.get("min").asInt() : null;
                        m.max = payload.hasNonNull("max") ? payload.get("max").asInt() : null;
                        m.hasContext = true;
                    }
                }
                case "DECISION_MADE" -> {
                    Mutable m = bySeq.get(payload.path("requestSeq").asLong(-1));
                    if (m != null) {
                        m.chosen = payload.path("selectionSummary").asText("");
                        m.source = payload.path("source").asText("");
                        m.thinkMs = payload.path("thinkMs").asLong(0);
                        m.answered = true;
                    }
                }
                case "DECISION_SKIPPED" -> {
                    Mutable m = bySeq.get(payload.path("requestSeq").asLong(-1));
                    if (m != null) {
                        m.chosen = null;
                        m.source = payload.path("reason").asText("SKIPPED");
                        m.answered = false;
                    }
                }
                default -> {
                }
            }
        }
        return ordered.stream().map(Mutable::toRecord).toList();
    }

    private static Mutable lastWithoutContext(List<Mutable> ordered) {
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (!ordered.get(i).hasContext) {
                return ordered.get(i);
            }
        }
        return null;
    }

    private static final class Mutable {
        private final long seq;
        private final int turn;
        private final String requestType;
        private int candidateCount;
        private Integer min;
        private Integer max;
        private String chosen;
        private String source;
        private long thinkMs;
        private boolean answered;
        private boolean hasContext;

        private Mutable(long seq, int turn, String requestType, int candidateCount) {
            this.seq = seq;
            this.turn = turn;
            this.requestType = requestType;
            this.candidateCount = candidateCount;
        }

        private ReplayDecision toRecord() {
            return new ReplayDecision(seq, turn, requestType, candidateCount, min, max,
                    chosen, source, thinkMs, answered);
        }
    }

    private DecisionReplayReader() {
    }
}
