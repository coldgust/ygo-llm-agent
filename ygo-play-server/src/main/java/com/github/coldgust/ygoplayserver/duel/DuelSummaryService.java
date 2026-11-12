package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoduellog.ingest.JsonlDuelParser;
import com.github.coldgust.ygoduellog.model.DecisionEpisode;
import com.github.coldgust.ygoduellog.model.DuelRecord;
import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplayserver.config.LightRagProperties;
import com.github.coldgust.ygoplayserver.config.DuelSummaryProperties;
import com.github.coldgust.ygoplayserver.duel.summary.DuelNarrative;
import com.github.coldgust.ygoplayserver.duel.summary.DuelSummarizer;
import com.github.coldgust.ygoplayserver.duel.summary.DuelSummaryPrompts;
import com.github.coldgust.ygoplayserver.duel.summary.DuelTraceReader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DuelSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DuelSummaryService.class);

    private final String endpoint;
    private final String apiKey;
    private final boolean enabled;
    private final boolean llmMode;
    private final int narrativeChars;
    private final DuelSummarizer summarizer;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DuelSummaryService(LightRagProperties lightRag, DuelSummaryProperties properties,
                              ChatClient chat, VerboseLog trace) {
        String base = lightRag.url().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/documents/text";
        this.apiKey = lightRag.apiKey();
        this.enabled = properties.enabled();
        String mode = properties.mode().toLowerCase(Locale.ROOT);
        this.llmMode = !"template".equals(mode) && !"off".equals(mode);
        this.narrativeChars = properties.narrativeMaxChars();
        this.summarizer = chat == null ? null : new DuelSummarizer(chat,
                properties.maxTokens(),
                Duration.ofMillis(properties.timeoutMs()), trace);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        if (this.enabled && llmMode && this.summarizer == null) {
            log.info("对局复盘：LLM 未配置，退回模板摘要（只记事实，不含判断）");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    record Draft(String text, boolean worth, boolean fromLlm) {
    }

    public void ingestAsync(String duelId, DuelRunSpec spec, DuelRunResult result) {
        if (!enabled) {
            return;
        }
        executor.submit(() -> {
            try {
                Draft draft = draft(duelId, spec, result);
                post(draft.text(), "duel:" + duelId);
                log.info("对局复盘已写入 RAG：duel={}（{} 字，{}）", duelId, draft.text().length(),
                        draft.fromLlm() ? "LLM 复盘" : "模板兜底");
            } catch (RuntimeException e) {
                log.warn("对局复盘写入失败（duel={}）：{}", duelId, e.toString());
            }
        });
    }

    public void ingestJudgedAsync(String duelId, DuelRunSpec spec, DuelRunResult result) {
        if (!enabled) {
            return;
        }
        executor.submit(() -> {
            try {
                Draft draft = draft(duelId, spec, result);
                if (!draft.fromLlm()) {
                    log.info("模型判定入库：LLM 不可用（模板兜底不含判断），按策略不入库 duel={}", duelId);
                    return;
                }
                if (!draft.worth()) {
                    log.info("模型判定本局无入库价值，跳过：duel={}", duelId);
                    return;
                }
                post(draft.text(), "duel:" + duelId);
                log.info("模型判定值得入库，复盘已写入 RAG：duel={}（{} 字）", duelId, draft.text().length());
            } catch (RuntimeException e) {
                log.warn("对局复盘写入失败（duel={}）：{}", duelId, e.toString());
            }
        });
    }

    String build(String duelId, DuelRunSpec spec, DuelRunResult result) {
        return draft(duelId, spec, result).text();
    }

    Draft draft(String duelId, DuelRunSpec spec, DuelRunResult result) {
        DuelRecord record = readRecord(spec);
        DuelTraceReader.TraceView trace = DuelTraceReader.read(spec.tracePath());
        DuelNarrative narrative = DuelNarrative.build(duelId, spec, result, record, trace, narrativeChars);

        Optional<DuelSummarizer.Review> review = llmMode && summarizer != null
                ? summarizer.summarize(narrative.facts(), narrative.timeline())
                : Optional.empty();
        if (review.isEmpty()) {
            log.info("复盘正文退回模板（duel={}，trace 动作 {} 条）", duelId, trace.actions().size());
            return new Draft(buildTemplate(duelId, spec, result, record, trace), false, false);
        }
        StringBuilder sb = new StringBuilder(header(duelId, spec, result, record));
        sb.append("---\n\n");
        sb.append(review.get().body()).append('\n');
        return new Draft(sb.toString(), review.get().worthStoring(), true);
    }

    String buildTemplate(String duelId, DuelRunSpec spec, DuelRunResult result,
                         DuelRecord record, DuelTraceReader.TraceView trace) {
        StringBuilder sb = new StringBuilder(header(duelId, spec, result, record));
        sb.append("---\n");
        sb.append("（本次复盘由模板生成：模型不可用或输出不可用，只记录事实、不含判断）\n");
        sb.append("关键决策：\n");
        if (!trace.actions().isEmpty()) {
            appendTraceHighlights(sb, trace);
        } else if (record != null) {
            appendKeyDecisions(sb, record);
        } else {
            sb.append("- （无可用记录）\n");
        }
        return sb.toString();
    }

    private void appendTraceHighlights(StringBuilder sb, DuelTraceReader.TraceView trace) {
        int lastTurn = Integer.MIN_VALUE;
        int perTurn = 0;
        for (DuelTraceReader.Action action : trace.actions()) {
            if (action.turn() != lastTurn) {
                lastTurn = action.turn();
                perTurn = 0;
            }
            // 只留"我方主动做了什么"，不响应类不进模板（信息量太低）
            if (action.submitted() == null || action.submitted().isBlank()
                    || action.submitted().contains("不响应")) {
                continue;
            }
            if (perTurn >= 3) {
                continue;
            }
            perTurn++;
            sb.append("- T").append(action.turn())
                    .append(" [").append(action.level()).append("] ")
                    .append(Texts.flattenClip(action.submitted(), 70)).append('\n');
        }
    }

    private void appendKeyDecisions(StringBuilder sb, DuelRecord record) {
        int lastTurn = Integer.MIN_VALUE;
        int perTurn = 0;
        for (DecisionEpisode episode : record.episodes()) {
            if (episode.chosen() == null || episode.chosen().isBlank()) {
                continue;
            }
            if (!interesting(episode.requestType())) {
                continue;
            }
            if (episode.turn() != lastTurn) {
                lastTurn = episode.turn();
                perTurn = 0;
            }
            if (perTurn >= 3) {
                continue;
            }
            perTurn++;
            sb.append("- T").append(episode.turn()).append(' ').append(episode.phase())
                    .append(' ').append(shortType(episode.requestType())).append(": ")
                    .append(Texts.flattenClip(episode.chosen(), 60)).append('\n');
        }
    }

    private static String header(String duelId, DuelRunSpec spec, DuelRunResult result, DuelRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("domain: duel\n");
        sb.append("duel: ").append(duelId).append('\n');
        sb.append("date: ").append(LocalDate.now()).append('\n');
        sb.append("myArchetype: ").append(spec.archetype() == null || spec.archetype().isBlank()
                ? "未知" : spec.archetype()).append('\n');
        sb.append("move: ").append("first".equalsIgnoreCase(spec.move()) ? "先手" : "后手").append('\n');
        if (result != null && result.duelResult() != null) {
            sb.append("result: ").append(result.duelResult().winner())
                    .append("（").append(result.duelResult().reason()).append("）\n");
        }
        if (result != null) {
            sb.append("answered: ").append(result.stats().answered())
                    .append("，fallbacks: ").append(result.stats().fallbacks()).append('\n');
        }
        if (record != null && record.meta() != null) {
            sb.append("turns: ").append(record.meta().turns()).append('\n');
        }
        return sb.toString();
    }

    private DuelRecord readRecord(DuelRunSpec spec) {
        if (spec == null || spec.duelLogPath() == null) {
            return null;
        }
        try {
            return new JsonlDuelParser().parse(spec.duelLogPath());
        } catch (Exception e) {
            log.debug("对局日志不可解析（{}）：{}", spec.duelLogPath(), e.toString());
            return null;
        }
    }

    private static boolean interesting(String requestType) {
        if (requestType == null) {
            return false;
        }
        String type = requestType.toLowerCase(Locale.ROOT);
        return type.contains("summon") || type.contains("activate") || type.contains("attack")
                || type.contains("chain") || type.contains("select");
    }

    private static String shortType(String requestType) {
        if (requestType == null) {
            return "?";
        }
        return requestType.replace("Request", "");
    }

    private void post(String text, String source) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            payload.put("file_source", source);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("LightRAG HTTP " + response.statusCode()
                        + ": " + Texts.flattenClip(response.body(), 200));
            }
        } catch (Exception e) {
            throw new IllegalStateException("写 LightRAG 失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
