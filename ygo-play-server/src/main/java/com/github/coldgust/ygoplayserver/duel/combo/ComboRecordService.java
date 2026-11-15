package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.LightRagContentRetriever;
import com.github.coldgust.ygoagent.llm.LightRagSettings;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.service.NarratorAgent;
import com.github.coldgust.ygoplay.ComboRecording;
import com.github.coldgust.ygoplayserver.config.ComboProperties;
import com.github.coldgust.ygoplayserver.config.LightRagProperties;
import com.github.coldgust.ygoplayserver.config.LlmProperties;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * combo 实战录入:解析对局记录里用户回合的逐步操作 → LLM 摘要 → 落 PG {@code combo_record}。
 *
 * <p>迁移要点(与原 JdbcClient 版逐条对应):
 * <ul>
 *   <li>写入走 {@link ComboRecordEntity#record} + 仓库 save(IDENTITY 替代 {@code RETURNING id});</li>
 *   <li>SSE 负载是 record,不再手工拼 {@code Map};</li>
 *   <li>建表 DDL 迁到 Flyway,本类不再有 {@code @PostConstruct} 建表;</li>
 *   <li>D7 不变:录入是旁路,任何异常都转成 SSE 错误,绝不向上抛。</li>
 * </ul>
 */
@Service
public class ComboRecordService {

    private static final Logger log = LoggerFactory.getLogger(ComboRecordService.class);

    private static final int SUMMARY_MAX_TOKENS = 1_000;
    private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(60);
    private static final int STEPS_PREVIEW = 8;

    private final ObjectProvider<ComboRecordRepository> comboRepository;
    private final DuelEventHub eventHub;
    private volatile NarratorAgent chat;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final VerboseLog trace;
    private final LightRagContentRetriever rulebookRetriever;

    public ComboRecordService(ObjectProvider<ComboRecordRepository> comboRepository, DuelEventHub eventHub,
                              LlmProperties llm, ComboProperties combo, VerboseLog trace,
                              LightRagProperties lightRag) {
        this.comboRepository = comboRepository;
        this.eventHub = eventHub;
        this.apiKey = llm.apiKey();
        this.baseUrl = llm.baseUrl();
        this.model = combo.summaryModel() == null ? llm.model() : combo.summaryModel();
        this.trace = VerboseLog.offOr(trace);
        // combo 摘要的规则检索增强:LightRAG 未配置时为 null(narrate 退化为无检索)
        this.rulebookRetriever = lightRag == null || lightRag.url() == null || lightRag.url().isBlank()
                ? null
                : new LightRagContentRetriever(
                        new LightRagSettings(lightRag.url(), lightRag.apiKey(), lightRag.timeoutMs()),
                        "mix", 4);
    }

    public void record(ComboRecording recording, CardFacts facts) {
        String duelId = recording.duelId();
        try {
            doRecord(recording, facts);
        } catch (Exception e) {
            log.warn("combo 录入失败(duel={}):{}", duelId, e.toString());
            publish(duelId, new ComboRecordOutcome(duelId, 0, null, String.valueOf(e.getMessage())));
        }
    }

    private void doRecord(ComboRecording recording, CardFacts facts) throws Exception {
        List<ComboStepReader.Step> steps =
                ComboStepReader.readUserTurn(recording.duelLogPath(), facts);
        String duelId = recording.duelId();
        if (steps.isEmpty()) {
            log.info("combo 录入:用户回合没有可记录的操作(duel={})", duelId);
            publish(duelId, new ComboRecordOutcome(duelId, 0, "用户回合没有可记录的操作", null));
            return;
        }
        if (recording.archetype() == null || recording.archetype().isBlank()) {
            // 启动请求侧已强制必填(DuelSessionManager);这里是防御:缺主题一律不入库
            log.warn("combo 录入缺少卡组主题,放弃入库(duel={})", duelId);
            publish(duelId, new ComboRecordOutcome(duelId, 0, "缺少卡组主题,未录入", null));
            return;
        }
        String archetype = recording.archetype();
        List<String> descriptions = steps.stream().map(ComboStepReader.Step::desc).toList();

        String summary = null;
        String summarySource = "NONE";
        String keyCards = null;
        NarratorAgent client = chat();
        if (client != null) {
            try {
                String raw = client.comboSummary(archetype, ComboSummaryPrompts.steps(descriptions));
                String text = raw == null ? "" : raw.trim();
                if (ComboSummaryPrompts.looksUsable(text)) {
                    summary = text;
                    summarySource = "LLM";
                    keyCards = ComboSummaryPrompts.keyCardsOf(text);
                } else {
                    log.warn("combo 摘要输出不可用({} 字),只存步骤", text.length());
                }
            } catch (RuntimeException e) {
                log.warn("combo 摘要生成失败,只存步骤: {}", e.toString());
            }
        }

        Long id = insert(duelId, archetype, steps, summary, keyCards, summarySource);
        log.info("combo 录入完成(duel={}):{} 步 → combo_record#{},摘要 {},关键卡牌 [{}]",
                duelId, steps.size(), id,
                summary == null ? "无(" + summarySource + ")" : summary.length() + " 字",
                keyCards == null ? "无" : keyCards);

        publish(duelId, new ComboRecordedEvent(duelId, id == null ? 0L : id, archetype,
                steps.size(), preview(descriptions), summary, summarySource));
    }

    private Long insert(String duelId, String archetype, List<ComboStepReader.Step> steps,
                        String summary, String keyCards, String summarySource) {
        ComboRecordRepository repository = requireRepository();
        List<ComboStepRow> rows = steps.stream()
                .map(step -> new ComboStepRow(step.seq(), step.turn(), step.phase(), step.kind(),
                        step.desc(), step.passcode(), step.fromZone(), step.toZone(),
                        step.position(), step.player()))
                .toList();
        ComboRecordEntity saved = repository.save(
                ComboRecordEntity.record(duelId, archetype, rows, summary, keyCards, summarySource));
        return saved.getId();
    }

    private ComboRecordRepository requireRepository() {
        ComboRecordRepository repository = comboRepository == null ? null : comboRepository.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("PostgreSQL 不可用(combo 录入改为必须存库)");
        }
        return repository;
    }

    /** 摘要预览与 REST 查询共用同一截断常量(STEPS_PREVIEW)。 */
    private static List<String> preview(List<String> descriptions) {
        return descriptions.subList(0, Math.min(STEPS_PREVIEW, descriptions.size()));
    }

    public Optional<ComboRecordView> findLatest(String duelKey) {
        ComboRecordRepository repository = comboRepository == null ? null : comboRepository.getIfAvailable();
        if (repository == null || duelKey == null || duelKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return repository.findFirstByDuelIdOrderByIdDesc(duelKey)
                    .map(entity -> ComboRecordView.of(entity, STEPS_PREVIEW));
        } catch (RuntimeException e) {
            log.debug("combo 录入查询失败(duel={}):{}", duelKey, e.toString());
            return Optional.empty();
        }
    }

    private NarratorAgent chat() {
        NarratorAgent client = chat;
        if (client != null) {
            return client;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (chat == null) {
                chat = new NarratorAgent(AgentModels.openAi(
                        new LlmEndpoint(baseUrl, apiKey, model),
                        ChatOptions.narrative(SUMMARY_MAX_TOKENS), SUMMARY_TIMEOUT, false, trace),
                        ComboSummaryPrompts.SYSTEM, rulebookRetriever);
            }
            return chat;
        }
    }

    private void publish(String duelId, Object payload) {
        try {
            eventHub.publish(duelId, "COMBO_RECORDED", payload);
        } catch (RuntimeException ignored) {
        }
    }
}