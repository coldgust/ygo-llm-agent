package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.planexec.CardFacts;
import com.github.coldgust.ygoagent.decision.planexec.CardRagFactService;
import com.github.coldgust.ygoagent.decision.planexec.PlannerSettings;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;

public final class DuelRunnerMain {

    private static final Logger log = LoggerFactory.getLogger(DuelRunnerMain.class);

    public static void main(String[] args) throws Exception {
        CliOptions cli = CliOptions.parse(args);
        RuntimeConfig cfg = RuntimeConfig.resolve(cli);

        var planExec = PlanExecSettingsResolver.resolve(
                DotEnv.load(Path.of(cli.envPath() == null ? ".env" : cli.envPath())))
                .orElse(null);

        DuelRunSpec spec = new DuelRunSpec(
                cfg.host(), cfg.port(), cfg.name(), cfg.move(),
                null,
                cfg.ydkPath() == null ? null : Path.of(cfg.ydkPath()),
                cfg.archetype(),
                false,
                cfg.duelLogPath(), cfg.tracePath(),
                deckStrategy());

        log.info("连接 {}:{}", cfg.host(), cfg.port());
        log.info("对局记录 → {}", cfg.duelLogPath());
        log.info("决策轨迹 → {}", cfg.tracePath());
        if (planExec != null) {
            log.info("v6 Planner–Executor 启用：model={} card-rag={}",
                    planExec.model(), planExec.cardRagPath());
        } else {
            log.info("v6 Planner–Executor 未启用（LLM_API_KEY 未配置或 PLAN_EXEC_ENABLED=false），走 Reflex/Tactical 快路径");
        }

        DuelRunner runner = new DuelRunner(planExec, null, reporter(planExec));
        DuelRunResult result = runner.run(spec, new AtomicReference<>(), () -> false);

        log.info("对局结束：应答 {} 次，保底 {} 次，结果 {}",
                result.stats().answered(), result.stats().fallbacks(),
                result.duelResult() == null ? "未知"
                        : result.duelResult().winner() + "/" + result.duelResult().reason());
    }

    private static DuelListener reporter(
            PlannerSettings planExec) {
        String target = System.getenv("BATTLE_REPORT");
        if (target == null || target.isBlank() || "0".equals(target)) {
            return null;
        }
        Path path = Path.of("1".equals(target) ? "/tmp/ygo-live/battlefield.log" : target);
        Path rag = planExec == null
                ? Path.of("data/card_rag/cards.jsonl") : planExec.cardRagPath();
        CardFacts facts = null;
        try {
            facts = CardRagFactService.load(rag);
        } catch (Exception e) {
            log.warn("实况播报未加载卡库（{}），只报卡号: {}", rag, e.toString());
        }
        try {
            log.info("实况战场播报 → {}", path);
            return BattlefieldReporter.open(path, facts);
        } catch (Exception e) {
            log.warn("实况播报启动失败: {}", e.toString());
            return null;
        }
    }

    private static String deckStrategy() {
        String file = System.getenv("DECK_STRATEGY_FILE");
        if (file != null && !file.isBlank()) {
            try {
                String text = Files.readString(Path.of(file));
                log.info("已从 {} 读取卡组思路（{} 字）", file, text.strip().length());
                return text;
            } catch (Exception e) {
                log.warn("读取 DECK_STRATEGY_FILE 失败: {}", e.toString());
            }
        }
        String inline = System.getenv("DECK_STRATEGY");
        return inline == null || inline.isBlank() ? null : inline;
    }

    private DuelRunnerMain() {
    }
}
