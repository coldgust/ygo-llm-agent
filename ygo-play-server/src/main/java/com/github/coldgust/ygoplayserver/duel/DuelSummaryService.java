package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoagent.decision.planexec.llm.OpenAiCompatChat;
import com.github.coldgust.ygoduellog.ingest.JsonlDuelParser;
import com.github.coldgust.ygoduellog.model.DecisionEpisode;
import com.github.coldgust.ygoduellog.model.DuelRecord;
import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplayserver.duel.summary.DuelNarrative;
import com.github.coldgust.ygoplayserver.duel.summary.DuelSummarizer;
import com.github.coldgust.ygoplayserver.duel.summary.DuelSummaryPrompts;
import com.github.coldgust.ygoplayserver.duel.summary.DuelTraceReader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
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

/**
 * 对局复盘 → LightRAG。
 *
 * <p>定位：对局记忆是<b>叙事记忆</b>。每局结束后由 LLM 写一份复盘，写入同一个 workspace，
 * 来源前缀 {@code duel:<id>}；模型需要参考历史经验时通过 {@code search_duels} 自主检索。
 *
 * <p>分工是本类的要点：
 * <ul>
 *   <li><b>代码</b>只负责"不许模型猜"的部分——素材整理（{@link DuelNarrative}）与文档头
 *       （id / 日期 / 主题 / 先后手 / 胜负 / 应答次数）；</li>
 *   <li><b>LLM</b> 负责"关键信息"——走向、转折点、失误与代价、下次怎么办
 *       （{@link DuelSummaryPrompts}）；</li>
 *   <li>LLM 不可用或输出不合结构时，退回确定性模板（{@code buildTemplate}），
 *       保证"至少有一条能检索的记录"，但那种摘要不含判断，只作兜底。</li>
 * </ul>
 *
 * <p>模式由 {@code DUEL_SUMMARY_MODE} 控制：{@code llm}（默认）/ {@code template}（不调 LLM）。
 * 是否写库由每次开局请求的 {@code archivePolicy} 决定（见 {@link DuelRequest}）：
 * {@code always} 直接写；{@code auto} 由模型在复盘末尾判定价值，判定不值得就不写；
 * {@code ask} 挂起等用户决定（确认后走 {@link #ingestAsync}）；缺省 {@code never} 不写。
 *
 * <p>装配见 {@code SummaryConfig}：LLM 凭据齐全时注入 {@code ChatClient}，否则退化为模板摘要。
 */
public class DuelSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DuelSummaryService.class);

    /** 逐回合记录的字符预算：给足上下文，又不至于把 32k 窗口的模型顶满。 */
    private static final int DEFAULT_NARRATIVE_CHARS = 8_000;

    private final String endpoint;
    private final String apiKey;
    private final boolean enabled;
    private final boolean llmMode;
    private final int narrativeChars;
    private final DuelSummarizer summarizer;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 只写模板摘要（测试与"不想花 LLM 配额"的部署用）。 */
    public DuelSummaryService(Environment environment) {
        this(environment, null);
    }

    /**
     * @param chat 复盘用的模型客户端；null = 退化为模板摘要
     */
    public DuelSummaryService(Environment environment, ChatClient chat) {
        String base = environment.getProperty("LIGHTRAG_URL", "http://localhost:9621").trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/documents/text";
        this.apiKey = environment.getProperty("LIGHTRAG_API_KEY", "");
        this.enabled = !"false".equalsIgnoreCase(
                environment.getProperty("DUEL_SUMMARY_ENABLED", "true"));
        String mode = environment.getProperty("DUEL_SUMMARY_MODE", "llm").trim().toLowerCase(Locale.ROOT);
        this.llmMode = !"template".equals(mode) && !"off".equals(mode);
        this.narrativeChars = intOf(environment, "DUEL_NARRATIVE_MAX_CHARS", DEFAULT_NARRATIVE_CHARS);
        this.summarizer = chat == null ? null : new DuelSummarizer(chat,
                intOf(environment, "DUEL_SUMMARY_MAX_TOKENS", 2_000),
                Duration.ofMillis(intOf(environment, "DUEL_SUMMARY_TIMEOUT_MS", 60_000)));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        if (this.enabled && llmMode && this.summarizer == null) {
            log.info("对局复盘：LLM 未配置，退回模板摘要（只记事实，不含判断）");
        }
    }

    /** 按环境构建（LLM 配置齐全时才建客户端）。 */
    public static DuelSummaryService fromEnvironment(Environment environment) {
        String apiKey = environment.getProperty("LLM_API_KEY", "");
        ChatClient chat = null;
        if (apiKey != null && !apiKey.isBlank()) {
            chat = new OpenAiCompatChat(
                    environment.getProperty("LLM_BASE_URL", "https://api.deepseek.com"),
                    apiKey,
                    environment.getProperty("DUEL_SUMMARY_MODEL",
                            environment.getProperty("LLM_MODEL", "deepseek-chat")));
        }
        return new DuelSummaryService(environment, chat);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 一次复盘的产出：最终文档 + 模型是否判定值得入库 + 正文是否真出自 LLM。 */
    record Draft(String text, boolean worth, boolean fromLlm) {
    }

    /** 对局结束后异步写复盘；失败只记日志，不影响对局结果。（全部入库 / 询问后确认入库走这里） */
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

    /**
     * 模型判定入库（auto 策略）：先由 LLM 写复盘并给出「入库判定」，判定不值得就不写库。
     *
     * <p>拿不到判定就不写：LLM 不可用退回的模板不含判断，替模型做"值得入库"的决定
     * 等于架空这个策略——那一局的素材仍在 trace / duel-log 文件里，没有丢失。
     */
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

    /**
     * 组装最终文档：确定性文档头 + 复盘正文。
     *
     * <p>文档头固定用 {@code key: value} 形式（供域过滤与结构化检索），正文是 Markdown。
     */
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

    /**
     * 确定性模板：只记事实，不做判断。
     *
     * <p>它的价值不是"够用"，而是**保证每条对局都有可检索记录**——LLM 配额耗尽、端点故障、
     * 输出跑偏时，历史里至少还留有"这局打了什么"。真正有用的判断仍以 LLM 复盘为准。
     */
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

    /** 模板路径下的"关键决策"：优先用轨迹，因为它是可读的。 */
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
                    .append(clip(action.submitted(), 70)).append('\n');
        }
    }

    /** 没有轨迹时的退路：读日志里的选择串（可读性差，仅作最后兜底）。 */
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
                    .append(clip(episode.chosen(), 60)).append('\n');
        }
    }

    /** 文档头：这些字段不许模型猜，全部由代码给。 */
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
                        + ": " + clip(response.body(), 200));
            }
        } catch (Exception e) {
            throw new IllegalStateException("写 LightRAG 失败: " + e.getMessage(), e);
        }
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static int intOf(Environment environment, String key, int fallback) {
        try {
            String raw = environment.getProperty(key);
            return raw == null || raw.isBlank() ? fallback : Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
