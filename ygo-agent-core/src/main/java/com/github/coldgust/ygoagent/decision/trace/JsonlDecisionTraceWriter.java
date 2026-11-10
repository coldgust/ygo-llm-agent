package com.github.coldgust.ygoagent.decision.trace;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>同步写但量级很小（每决策一行），失败只告警不影响决策。
 */
public final class JsonlDecisionTraceWriter implements DecisionTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlDecisionTraceWriter.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private final BufferedWriter writer;

    public JsonlDecisionTraceWriter(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建决策轨迹文件: " + file, e);
        }
    }

    @Override
    public synchronized void event(String kind, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.putAll(payload);
        writeRow(row);
    }

    @Override
    public synchronized void append(DecisionPoint point, DuelDecision decision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "DECISION");
        row.put("seq", point.seq());
        row.put("turn", point.turn());
        row.put("phase", String.valueOf(point.phase()));
        row.put("requestKind", point.kind());
        row.put("requestLabel", point.kind().label());
        row.put("stateHash", point.stateHash());
        row.put("requestHash", point.requestHash());
        row.put("pathKind", decision.pathKind());
        row.put("pathLabel", decision.pathKind().label());
        row.put("confidence", decision.confidence());
        row.put("intent", String.valueOf(decision.intent()));
        row.put("conclusion", decision.explanation().conclusion());
        row.put("utilityTerms", decision.explanation().utilityTerms());
        row.put("timingMs", Map.of(
                "reflex", decision.timing().reflexNanos() / 1_000_000.0,
                "tactical", decision.timing().tacticalNanos() / 1_000_000.0,
                "total", decision.timing().totalNanos() / 1_000_000.0));
        writeRow(row);
    }

    private void writeRow(Map<String, Object> row) {
        try {
            writer.write(MAPPER.writeValueAsString(row));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            log.warn("决策轨迹写入失败: {}", e.getMessage());
        }
    }
}
