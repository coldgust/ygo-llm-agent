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

/**
 * 实机入口：连房间 → 上传卡组 → 就绪 → 决策循环 → 打印统计。
 *
 * <pre>
 * java DuelRunnerMain [host] [port] [name] [.ydk] [first|second]
 *      [--trace 路径] [--duel-log 路径]
 *      [--archetype SKY_STRIKER] [--combos 路径] [--library 目录]
 *      [--env .env]
 * </pre>
 *
 * 配置优先级：CLI &gt; 环境变量/.env &gt; 默认值（见 {@link RuntimeConfig}）。
 * v6 Planner–Executor 由 PLAN_EXEC_ENABLED / LLM_API_KEY 等 env 键启用（见 PlanExecSettingsResolver）。
 */
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

    /**
     * 实况战场播报：{@code BATTLE_REPORT=1}（默认写 /tmp/ygo-live/battlefield.log）
     * 或指定文件路径时启用。每个决策点输出规划器看到的同一份局面 + 独立张数核对。
     */
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

    /**
     * 卡组思路（CLI 通道）：`DECK_STRATEGY_FILE` 指向文本文件优先，其次 `DECK_STRATEGY` 直接给文本。
     * <p>Web 通道由前端文本框传入（见 DuelRequest.deckStrategy）。
     */
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
