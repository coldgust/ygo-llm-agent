package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.llm.LangChain4jChat;
import com.github.coldgust.ygoagent.llm.ResilientChatClient;
import com.github.coldgust.ygoplay.ComboRecording;
import com.github.coldgust.ygoplayserver.config.ComboProperties;
import com.github.coldgust.ygoplayserver.config.LlmProperties;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ComboRecordService {

    private static final Logger log = LoggerFactory.getLogger(ComboRecordService.class);

    private static final int SUMMARY_MAX_TOKENS = 1_000;
    private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(60);
    private static final int STEPS_PREVIEW = 8;

    private final ObjectProvider<JdbcClient> jdbc;
    private final DuelEventHub eventHub;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile ChatClient chat;
    private volatile boolean tableReady;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final VerboseLog trace;

    public ComboRecordService(ObjectProvider<JdbcClient> jdbc, DuelEventHub eventHub,
                              LlmProperties llm, ComboProperties combo, VerboseLog trace) {
        this.jdbc = jdbc;
        this.eventHub = eventHub;
        this.apiKey = llm.apiKey();
        this.baseUrl = llm.baseUrl();
        this.model = combo.summaryModel() == null ? llm.model() : combo.summaryModel();
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
    }


    @jakarta.annotation.PostConstruct
    void initTable() {
        JdbcClient client = jdbc.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            ensureTable(client);
            tableReady = true;
        } catch (RuntimeException e) {
            log.warn("combo_record 建表/补列失败（首次录入时会重试）: {}", e.toString());
        }
    }

    public void record(ComboRecording recording, CardFacts facts) {
        String duelId = recording.duelId();
        try {
            doRecord(recording, facts);
        } catch (Exception e) {
            log.warn("combo 录入失败（duel={}）：{}", duelId, e.toString());
            publish(duelId, Map.of("duelId", duelId, "count", 0, "error", String.valueOf(e.getMessage())));
        }
    }

    private void doRecord(ComboRecording recording, CardFacts facts) throws Exception {
        List<ComboStepReader.Step> steps =
                ComboStepReader.readUserTurn(recording.duelLogPath(), facts);
        String duelId = recording.duelId();
        if (steps.isEmpty()) {
            log.info("combo 录入：用户回合没有可记录的操作（duel={}）", duelId);
            publish(duelId, Map.of("duelId", duelId, "count", 0,
                    "reason", "用户回合没有可记录的操作"));
            return;
        }
        if (recording.archetype() == null || recording.archetype().isBlank()) {
            // 启动请求侧已强制必填（DuelSessionManager）；这里是防御：缺主题一律不入库
            log.warn("combo 录入缺少卡组主题，放弃入库（duel={}）", duelId);
            publish(duelId, Map.of("duelId", duelId, "count", 0,
                    "reason", "缺少卡组主题，未录入"));
            return;
        }
        String archetype = recording.archetype();
        List<String> descriptions = steps.stream().map(ComboStepReader.Step::desc).toList();

        String summary = null;
        String summarySource = "NONE";
        String keyCards = null;
        ChatClient client = chat();
        if (client != null) {
            try {
                String raw = client.complete(
                        List.of(SystemMessage.from(ComboSummaryPrompts.SYSTEM),
                                UserMessage.from(ComboSummaryPrompts.user(archetype, descriptions))),
                        ChatClient.ChatOptions.narrative(SUMMARY_MAX_TOKENS, SUMMARY_TIMEOUT));
                String text = raw == null ? "" : raw.trim();
                if (ComboSummaryPrompts.looksUsable(text)) {
                    summary = text;
                    summarySource = "LLM";
                    keyCards = ComboSummaryPrompts.keyCardsOf(text);
                } else {
                    log.warn("combo 摘要输出不可用（{} 字），只存步骤", text.length());
                }
            } catch (RuntimeException e) {
                log.warn("combo 摘要生成失败，只存步骤: {}", e.toString());
            }
        }

        Long id = insert(duelId, archetype, steps, summary, keyCards, summarySource);
        log.info("combo 录入完成（duel={}）：{} 步 → combo_record#{}，摘要 {}，关键卡牌 [{}]",
                duelId, steps.size(), id,
                summary == null ? "无（" + summarySource + "）" : summary.length() + " 字",
                keyCards == null ? "无" : keyCards);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("duelId", duelId);
        payload.put("recordId", id);
        payload.put("archetype", archetype);
        payload.put("count", steps.size());
        payload.put("stepPreviews", descriptions.subList(0, Math.min(STEPS_PREVIEW, descriptions.size())));
        payload.put("summary", summary);
        payload.put("summarySource", summarySource);
        publish(duelId, payload);
    }


    private Long insert(String duelId, String archetype, List<ComboStepReader.Step> steps,
                        String summary, String keyCards, String summarySource) {
        JdbcClient client = requireJdbc();
        if (!tableReady) {
            ensureTable(client);
            tableReady = true;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComboStepReader.Step step : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", step.seq());
            row.put("turn", step.turn());
            row.put("phase", step.phase());
            row.put("kind", step.kind());
            row.put("desc", step.desc());
            row.put("passcode", step.passcode());
            row.put("fromZone", step.fromZone());
            row.put("toZone", step.toZone());
            row.put("position", step.position());
            row.put("player", step.player());
            rows.add(row);
        }
        return client.sql("""
                        INSERT INTO combo_record
                            (duel_id, archetype, step_count, steps, summary, key_cards, summary_source)
                        VALUES (:duelId, :archetype, :stepCount, CAST(:steps AS jsonb),
                                :summary, :keyCards, :summarySource)
                        RETURNING id""")
                .param("duelId", duelId)
                .param("archetype", archetype)
                .param("stepCount", steps.size())
                .param("steps", mapper.writeValueAsString(rows))
                .param("summary", summary)
                .param("keyCards", keyCards)
                .param("summarySource", summarySource)
                .query(Long.class)
                .single();
    }

    private static void ensureTable(JdbcClient client) {
        client.sql("""
                CREATE TABLE IF NOT EXISTS combo_record (
                    id BIGSERIAL PRIMARY KEY,
                    duel_id TEXT NOT NULL,
                    archetype TEXT NOT NULL,
                    step_count INT NOT NULL,
                    steps JSONB NOT NULL,
                    summary TEXT,
                    key_cards TEXT,
                    summary_source TEXT NOT NULL DEFAULT 'NONE',
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""").update();
        // 老库升级：CREATE TABLE IF NOT EXISTS 不会补列
        client.sql("ALTER TABLE combo_record ADD COLUMN IF NOT EXISTS key_cards TEXT").update();
        client.sql("CREATE INDEX IF NOT EXISTS idx_combo_record_archetype "
                + "ON combo_record(archetype)").update();
    }

    private JdbcClient requireJdbc() {
        JdbcClient client = jdbc.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("PostgreSQL 不可用（combo 录入改为必须存库）");
        }
        return client;
    }


    private ChatClient chat() {
        ChatClient client = chat;
        if (client != null) {
            return client;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (chat == null) {
                chat = ChatClient.openAiCompatible(new LlmEndpoint(baseUrl, apiKey, model), trace);
            }
            return chat;
        }
    }


    public Optional<Map<String, Object>> findLatest(String duelKey) {
        JdbcClient client = jdbc.getIfAvailable();
        if (client == null || duelKey == null || duelKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return client.sql("""
                            SELECT id, archetype, step_count, summary, key_cards, summary_source, steps
                            FROM combo_record WHERE duel_id = :duelId
                            ORDER BY id DESC LIMIT 1""")
                    .param("duelId", duelKey)
                    .query((rs, rowNum) -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("duelId", duelKey);
                        payload.put("recordId", rs.getLong("id"));
                        payload.put("archetype", rs.getString("archetype"));
                        payload.put("count", rs.getInt("step_count"));
                        payload.put("stepPreviews", previewOf(rs.getString("steps")));
                        payload.put("summary", rs.getString("summary"));
                        payload.put("keyCards", rs.getString("key_cards"));
                        payload.put("summarySource", rs.getString("summary_source"));
                        return payload;
                    })
                    .optional();
        } catch (RuntimeException e) {
            log.debug("combo 录入查询失败（duel={}）：{}", duelKey, e.toString());
            return Optional.empty();
        }
    }

    private List<String> previewOf(String stepsJson) {
        List<String> descs = new ArrayList<>();
        if (stepsJson == null || stepsJson.isBlank()) {
            return descs;
        }
        try {
            mapper.readTree(stepsJson).forEach(node -> {
                String desc = node.path("desc").asText("");
                if (!desc.isBlank() && descs.size() < STEPS_PREVIEW) {
                    descs.add(desc);
                }
            });
        } catch (RuntimeException ignored) {
            // 坏 JSON：给空预览，摘要仍在
        }
        return descs;
    }

    private void publish(String duelId, Map<String, Object> payload) {
        try {
            eventHub.publish(duelId, "COMBO_RECORDED", payload);
        } catch (RuntimeException ignored) {
        }
    }
}
