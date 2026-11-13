package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.knowledge.JsonlCardFacts;
import com.github.coldgust.ygoagent.llm.LightRagSettings;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.trace.CallbackTraceWriter;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplay.DuelRunner;
import com.github.coldgust.ygoplay.DuelWebHooks;
import com.github.coldgust.ygoplayserver.card.PgCardFacts;
import com.github.coldgust.ygoplayserver.config.LightRagProperties;
import com.github.coldgust.ygoplayserver.config.PlanExecProperties;
import com.github.coldgust.ygoplayserver.duel.combo.ComboRecordService;
import com.github.coldgust.ygoplayserver.duel.combo.PgRecordedComboSource;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DuelRunnerFactory {

    private static final Logger log =
            LoggerFactory.getLogger(DuelRunnerFactory.class);

    private final DuelEventHub eventHub;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectProvider<ComboRecordService> comboRecords;
    private final PlanExecProperties planExecProps;
    private final LightRagProperties lightRagProps;
    private final VerboseLog trace;
    private volatile PgRecordedComboSource recordedSource;

    public DuelRunnerFactory(DuelEventHub eventHub) {
        this(eventHub, null, null, null, null, null);
    }

    @Autowired
    public DuelRunnerFactory(DuelEventHub eventHub, ObjectProvider<JdbcClient> jdbc,
                             ObjectProvider<ComboRecordService> comboRecords,
                             PlanExecProperties planExecProps, LightRagProperties lightRagProps,
                             VerboseLog trace) {
        this.eventHub = eventHub;
        this.jdbc = jdbc;
        this.comboRecords = comboRecords;
        this.planExecProps = planExecProps;
        this.lightRagProps = lightRagProps;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
    }

    public DuelExecutor create(DuelRunSpec spec) {
        // 设置来自类型化的 ygo.plan-exec.*（application.yaml 桥接 .env/环境变量），
        // 默认级联在 PlanExecProperties.toSettings；思考强度由 PipelineSettings 归一化
        var planExec = planExecProps == null || !planExecProps.v6Enabled()
                ? null : planExecProps.toSettings();
        String duelId = traceId(spec);
        // 卡牌事实优先走 PG（find_combos 的卡名解析与录入描述共用）；表不存在/库不可达时回退本地 jsonl
        CardFacts cardFacts = resolveCardFacts();
        ComboRecordService recordService = comboRecords == null ? null : comboRecords.getIfAvailable();
        DuelWebHooks hooks = new DuelWebHooks(
                new CallbackTraceWriter(
                        event -> eventHub.publish(duelId, String.valueOf(event.get("kind")), event)),
                spec.comboRecord() && recordService != null
                        ? recording -> recordService.record(recording, cardFacts)
                        : null,
                recordedSource(),
                (id, eventName, payload) -> eventHub.publish(id, eventName, payload));
        LightRagSettings lightRag = new LightRagSettings(lightRagProps.url(),
                lightRagProps.apiKey(), lightRagProps.timeoutMs());
        DuelRunner runner = new DuelRunner(planExec, hooks, null, cardFacts, lightRag, trace);
        return runner::run;
    }

    private CardFacts resolveCardFacts() {
        JdbcClient client = jdbc == null ? null : jdbc.getIfAvailable();
        if (client != null) {
            CardFacts pg = PgCardFacts.tryCreate(client);
            if (pg != null) {
                log.info("卡牌事实：PG（{} 张）", pg.knownCount());
                return pg;
            }
        }
        log.info("卡牌事实：PG 不可用，回退 data/card_rag/cards.jsonl");
        try {
            return JsonlCardFacts
                    .load(Path.of("data/card_rag/cards.jsonl"));
        } catch (Exception e) {
            return null; // 本地卡库也没有：录入描述退化为"未知卡(编号)"，对局照常
        }
    }

    private PgRecordedComboSource recordedSource() {
        PgRecordedComboSource source = recordedSource;
        if (source != null) {
            return source;
        }
        JdbcClient client = jdbc == null ? null : jdbc.getIfAvailable();
        if (client == null) {
            return null;
        }
        synchronized (this) {
            if (recordedSource == null) {
                PgRecordedComboSource probed = PgRecordedComboSource.tryCreate(client);
                // 空表不缓存：录进第一条后，下一局 start 就能查到（一次 count 查询，很便宜）
                if (probed != null && probed.available()) {
                    recordedSource = probed;
                }
                if (probed != null) {
                    log.info("combo 实战录入：PG（{}）", probed.available() ? "有数据" : "空表，待首次录入");
                }
                return probed;
            }
            return recordedSource;
        }
    }

    private static String traceId(DuelRunSpec spec) {
        String name = spec.tracePath().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
