package com.github.coldgust.ygoplayserver.duel.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

public final class DuelTraceReader {

    private static final Logger log = LoggerFactory.getLogger(DuelTraceReader.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    public record TurnPlanView(int turn, String goal, String thinking) {
    }

    public record Action(int turn, String level, String request, String submitted,
                         String rationale, boolean fallback) {
    }

    public record TraceView(Map<Integer, TurnPlanView> plans, List<Action> actions) {

        public static TraceView empty() {
            return new TraceView(Map.of(), List.of());
        }

        public boolean isEmpty() {
            return actions.isEmpty() && plans.isEmpty();
        }

        public TurnPlanView planFor(int turn) {
            return plans.get(turn);
        }
    }

    public static TraceView read(Path tracePath) {
        if (tracePath == null || !Files.isRegularFile(tracePath)) {
            return TraceView.empty();
        }
        try {
            return read(Files.readAllLines(tracePath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("轨迹不可读（{}）：{}", tracePath, e.toString());
            return TraceView.empty();
        }
    }

    public static TraceView read(List<String> lines) {
        Map<Integer, TurnPlanView> plans = new LinkedHashMap<>();
        Map<Long, String> rationaleBySeq = new LinkedHashMap<>();
        List<Action> actions = new ArrayList<>();
        // SUBMITTED 的 seq 与 EXEC_DECISION 的 seq 不同源：EXEC_DECISION 带 explanation 但没有可读提交，
        // SUBMITTED 有可读提交但没理由。按"同一 seq"关联不上时，退化为按 turn+request 就近取理由。
        Map<String, String> rationaleByTurnRequest = new LinkedHashMap<>();

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
            int turn = root.path("turn").asInt(0);
            switch (kind) {
                case "PLAN_EVENT" -> {
                    String goal = root.path("goal").asText("");
                    if (goal.isBlank()) {
                        continue; // 只有 PLAN_PUBLISHED 带 goal/thinking
                    }
                    plans.putIfAbsent(turn, new TurnPlanView(turn, goal,
                            root.path("thinking").asText("")));
                }
                case "EXEC_DECISION" -> {
                    String explanation = root.path("explanation").asText("");
                    if (explanation.isBlank()) {
                        continue;
                    }
                    rationaleBySeq.put(root.path("seq").asLong(-1), explanation);
                    rationaleByTurnRequest.put(key(turn, root.path("requestLabel").asText("")), explanation);
                }
                case "SUBMITTED" -> {
                    long seq = root.path("seq").asLong(-1);
                    String request = root.path("requestLabel").asText("");
                    String rationale = rationaleBySeq.getOrDefault(seq, "");
                    if (rationale.isBlank()) {
                        rationale = rationaleByTurnRequest.getOrDefault(key(turn, request), "");
                    }
                    actions.add(new Action(turn,
                            root.path("levelLabel").asText(""),
                            request,
                            root.path("submitted").asText(""),
                            rationale,
                            root.path("fallback").asBoolean(false)));
                }
                default -> {
                    // DECISION / PLANNER_* / POSITION_HINT 等不进复盘素材
                }
            }
        }
        return new TraceView(Map.copyOf(plans), List.copyOf(actions));
    }

    private static String key(int turn, String request) {
        return turn + "|" + request;
    }

    private DuelTraceReader() {
    }
}
