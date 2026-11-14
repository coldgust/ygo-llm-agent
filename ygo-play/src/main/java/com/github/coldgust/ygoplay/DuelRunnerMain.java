package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.knowledge.JsonlCardFacts;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class DuelRunnerMain {

    private static final Logger log = LoggerFactory.getLogger(DuelRunnerMain.class);

    public static void main(String[] args) throws Exception {
        CliOptions cli = CliOptions.parse(args);
        RuntimeConfig cfg = RuntimeConfig.resolve(cli);
        VerboseLog verbose = new VerboseLog(cfg.verbose());

        var planExec = cfg.planner();

        DuelRunSpec spec = new DuelRunSpec(
                cfg.host(), cfg.port(), cfg.name(), cfg.move(),
                null,
                cfg.ydkPath() == null ? null : Path.of(cfg.ydkPath()),
                cfg.archetype(),
                false,
                cfg.duelLogPath(), cfg.tracePath(),
                cfg.deckStrategy(), cfg.tuning());

        log.info("连接 {}:{}", cfg.host(), cfg.port());
        log.info("对局记录 → {}", cfg.duelLogPath());
        log.info("决策轨迹 → {}", cfg.tracePath());
        if (planExec != null) {
            log.info("v6 Planner–Executor 启用：model={} card-rag={}",
                    planExec.model(), planExec.cardRagPath());
        } else {
            log.info("v6 Planner–Executor 未启用（LLM_API_KEY 未配置或 PLAN_EXEC_ENABLED=false），走 Reflex/Tactical 快路径");
        }

        DuelRunner runner = new DuelRunner(planExec, null, reporter(cfg.battleReport(), planExec),
                null, cfg.lightRag(), verbose, cfg.runner());
        DuelRunResult result = runner.run(spec, new AtomicReference<>(), () -> false);

        log.info("对局结束：应答 {} 次，保底 {} 次，结果 {}",
                result.stats().answered(), result.stats().fallbacks(),
                result.duelResult() == null ? "未知"
                        : result.duelResult().winner() + "/" + result.duelResult().reason());
    }

    private static DuelListener reporter(String battleReport, PipelineSettings planExec) {
        if (battleReport == null || battleReport.isBlank() || "0".equals(battleReport)) {
            return null;
        }
        Path path = Path.of(battleReport);
        // 播报需要卡面名：planner 关闭时没有 CARD_RAG_PATH，降级为只报卡号
        Path rag = planExec == null ? null : planExec.cardRagPath();
        CardFacts facts = null;
        if (rag != null) {
            try {
                facts = JsonlCardFacts.load(rag);
            } catch (Exception e) {
                log.warn("实况播报未加载卡库（{}），只报卡号: {}", rag, e.toString());
            }
        } else {
            log.warn("实况播报未配置卡库路径（planner 未启用），只报卡号");
        }
        try {
            log.info("实况战场播报 → {}", path);
            return BattlefieldReporter.open(path, facts);
        } catch (Exception e) {
            log.warn("实况播报启动失败: {}", e.toString());
            return null;
        }
    }

    private DuelRunnerMain() {
    }
}
