package com.github.coldgust.ygoduellog.ingest;

import com.github.coldgust.ygoduellog.model.CandidateView;
import com.github.coldgust.ygoduellog.model.DecisionEpisode;
import com.github.coldgust.ygoduellog.model.DuelMeta;
import com.github.coldgust.ygoduellog.model.DuelRecord;
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
 * 将 recorder 的 JSONL 解析为对局元信息 + 决策 episode（L1 只做结构化，不解析完整 DuelState）。
 */
public final class JsonlDuelParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    public DuelRecord parse(Path file) throws IOException {
        return parse(file, Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public DuelRecord parse(Path file, List<String> lines) {
        String duelId = stripExtension(file.getFileName().toString());
        Map<Long, EpisodeBuilder> bySeq = new LinkedHashMap<>();
        List<EpisodeBuilder> ordered = new ArrayList<>();
        StateSummary latestState = StateSummary.empty();
        List<Integer> seats = new ArrayList<>();
        int turns = 0;
        String winner = null;
        String reason = null;

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
            JsonNode payload = root.path("payload");
            switch (kind) {
                case "DUEL_STARTED" -> {
                    payload.path("players").forEach(p -> seats.add(p.path("seat").asInt()));
                    turns = payload.path("totalTurns").asInt(0);
                }
                case "DUEL_ENDED" -> {
                    winner = textOrNull(payload.get("winner"));
                    reason = textOrNull(payload.get("reason"));
                    turns = Math.max(turns, payload.path("totalTurns").asInt(0));
                }
                case "STATE_SNAPSHOT" -> latestState = StateSummary.from(payload.path("duelState"));
                case "DECISION_REQUESTED" -> {
                    EpisodeBuilder builder = new EpisodeBuilder(
                            duelId,
                            root.path("seq").asLong(),
                            root.path("turn").asInt(),
                            root.path("phase").asText(""),
                            payload.path("requestType").asText(""),
                            payload.path("candidateCount").asInt(0),
                            latestState);
                    bySeq.put(builder.requestSeq, builder);
                    ordered.add(builder);
                }
                case "DECISION_CONTEXT" -> {
                    EpisodeBuilder builder = lastUnfinished(ordered);
                    if (builder != null) {
                        builder.candidateCount = payload.path("candidateCount").asInt(builder.candidateCount);
                        builder.candidates = parseCandidates(payload.path("candidates"));
                        builder.contextProvided = true;
                    }
                }
                case "DECISION_MADE" -> {
                    EpisodeBuilder builder = bySeq.get(payload.path("requestSeq").asLong(-1));
                    if (builder != null) {
                        builder.chosen = payload.path("selectionSummary").asText("");
                        builder.source = payload.path("source").asText("PLAYER");
                        builder.thinkMs = payload.path("thinkMs").asLong(0);
                        builder.answered = true;
                        builder.resolved = true;
                    }
                }
                case "DECISION_SKIPPED" -> {
                    EpisodeBuilder builder = bySeq.get(payload.path("requestSeq").asLong(-1));
                    if (builder != null) {
                        builder.chosen = null;
                        builder.source = payload.path("reason").asText("SKIPPED");
                        builder.answered = false;
                        builder.resolved = true;
                    }
                }
                default -> {
                }
            }
        }

        List<DecisionEpisode> episodes = ordered.stream().map(EpisodeBuilder::toEpisode).toList();
        int answered = (int) episodes.stream().filter(DecisionEpisode::answered).count();
        int context = (int) ordered.stream().filter(b -> b.contextProvided).count();
        boolean valid = !ordered.isEmpty() && ordered.stream().allMatch(b -> b.resolved);
        DuelMeta meta = new DuelMeta(duelId, file.toString(), turns, winner, reason, seats);
        return new DuelRecord(meta, episodes, episodes.size(), answered, context, valid);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<CandidateView> parseCandidates(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<CandidateView> out = new ArrayList<>();
        array.forEach(c -> out.add(new CandidateView(
                c.path("kind").asText(""),
                c.path("passcode").asLong(0),
                textOrNull(c.get("zone")),
                c.path("description").asLong(0))));
        return List.copyOf(out);
    }

    private static EpisodeBuilder lastUnfinished(List<EpisodeBuilder> ordered) {
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (!ordered.get(i).contextProvided) {
                return ordered.get(i);
            }
        }
        return null;
    }

    /** 可见局面摘要（只取组合匹配需要的字段）。 */
    private record StateSummary(List<Long> hand, List<Long> field, int selfLp, int oppLp) {

        private static StateSummary empty() {
            return new StateSummary(List.of(), List.of(), 0, 0);
        }

        private static StateSummary from(JsonNode state) {
            if (state == null || !state.isObject()) {
                return empty();
            }
            JsonNode players = state.path("players");
            JsonNode self = players.path("SELF");
            JsonNode opp = players.path("OPPONENT");
            List<Long> hand = new ArrayList<>();
            self.path("hand").forEach(card -> {
                long code = passcode(card.path("identity"));
                if (code > 0) {
                    hand.add(code);
                }
            });
            List<Long> field = new ArrayList<>();
            state.path("cards").forEach(card -> {
                String owner = card.path("zone").path("owner").asText("");
                String zone = card.path("zone").path("kind").asText("");
                if ("SELF".equals(owner) && (zone.contains("MONSTER") || zone.endsWith("SPELL_TRAP_ZONE"))) {
                    long code = passcode(card.path("identity"));
                    if (code > 0) {
                        field.add(code);
                    }
                }
            });
            return new StateSummary(List.copyOf(hand), List.copyOf(field),
                    self.path("lifePoints").asInt(0), opp.path("lifePoints").asInt(0));
        }

        private static long passcode(JsonNode identity) {
            if (identity == null || identity.isNull() || identity.isTextual()) {
                return 0;
            }
            return identity.path("passcode").asLong(0);
        }
    }

    private static final class EpisodeBuilder {
        private final String duelId;
        private final long requestSeq;
        private final int turn;
        private final String phase;
        private final String requestType;
        private int candidateCount;
        private List<CandidateView> candidates = List.of();
        private String chosen;
        private String source;
        private long thinkMs;
        private boolean answered;
        private boolean resolved;
        private boolean contextProvided;
        private final StateSummary state;

        private EpisodeBuilder(String duelId, long requestSeq, int turn, String phase,
                               String requestType, int candidateCount, StateSummary state) {
            this.duelId = duelId;
            this.requestSeq = requestSeq;
            this.turn = turn;
            this.phase = phase;
            this.requestType = requestType;
            this.candidateCount = candidateCount;
            this.state = state;
        }

        private DecisionEpisode toEpisode() {
            return new DecisionEpisode(duelId, requestSeq, turn, phase, requestType, candidateCount,
                    candidates, chosen, source, thinkMs, answered,
                    state.hand(), state.selfLp(), state.oppLp(), state.field());
        }
    }
}
