package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.llm.LightRagSettings;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CLI 的配置对象（本类是全进程唯一的 OS env + .env 读取点：键名经 {@link EnvSettings} 类型化读取，
 * 合并优先级由 dotenv-java 保证）。server 不用这里：server 由 Spring 的 @ConfigurationProperties 直接绑定
 * （见 ygo-play-server/config）。
 *
 * <p>默认值分级：调优参数（机器无关）用代码默认（{@code PipelineSettings.defaults}/{@code DuelTuning.defaults}/
 * {@code RunnerConfig.defaults()} 与 port/name/move 内联默认）；环境相关项（host/地址/路径/凭据）
 * 无代码默认，缺失 → 抛异常点名键。推荐值见 .env.example（cp .env.example .env）。
 *
 * @param host         YGOPro 服务地址（YGO_HOST，CLI 参数覆盖；环境相关必填）
 * @param port         YGOPro 服务端口（YGO_PORT 可覆盖；代码默认 7911）
 * @param name         房间内玩家名（YGO_PLAYER_NAME 可覆盖；代码默认 ygo-play-runner）
 * @param ydkPath      卡组 .ydk 文件路径（与粘贴文本二选一）
 * @param move         先后手：first / second（YGO_MOVE 可覆盖；代码默认 second）
 * @param archetype    卡组主题（combo 注入与检索的键；可空）
 * @param duelLogPath  对局记录 JSONL 落盘路径（DUEL_LOG_DIR 目录 + 时间戳文件名；环境相关必填）
 * @param tracePath    决策轨迹 JSONL 落盘路径（TRACE_DIR 目录 + 时间戳文件名；环境相关必填）
 * @param lightRag     LightRAG 知识栈连接；null = LIGHTRAG_URL 未配置，检索停用
 * @param battleReport 实况战场播报输出目标；空/0 = 关闭，其余 = 文件路径
 * @param deckStrategy 用户手写的卡组打法思路（注入规划器/决策器上下文；可空）
 * @param tuning       一局对局的运行调参（原始帧/看门狗/进房等待/combo 注入上限；代码默认 + env 覆盖）
 * @param planner      v6 规划器/执行器设置；null = 未启用（走 Reflex/Tactical 快路径）
 * @param verbose      全链路日志设置（缺席 = 关）
 * @param runner       决策器预算表（代码默认 + RUNNER_* 覆盖）
 */
public record RuntimeConfig(
        String host, int port, String name, String ydkPath, String move,
        String archetype,
        Path duelLogPath, Path tracePath,
        LightRagSettings lightRag,
        String battleReport,
        String deckStrategy,
        DuelTuning tuning,
        PipelineSettings planner,
        VerboseLogSettings verbose,
        RunnerConfig runner) {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static RuntimeConfig resolve(CliOptions cli) {
        String envPath = cli.envPath() != null ? cli.envPath() : ".env";
        EnvSettings env = EnvSettings.load(Path.of(envPath));
        String stamp = LocalDateTime.now().format(TS);

        // 连接参数：CLI 参数覆盖 env；host 环境相关必填，port/name/move 有代码默认
        String host = cli.host() != null ? cli.host() : required(env.ygoHost(), "YGO_HOST");
        int port = cli.port() != null ? cli.port() : orDefault(env.ygoPort(), 7911);
        String name = cli.name() != null ? cli.name() : orDefault(env.ygoPlayerName(), "ygo-play-runner");
        String move = cli.move() != null ? cli.move() : orDefault(env.ygoMove(), "second");

        Path duelLog = cli.duelLogPath() != null
                ? Path.of(cli.duelLogPath())
                : Path.of(required(env.duelLogDir(), "DUEL_LOG_DIR") + "/" + stamp + ".jsonl");
        Path trace = cli.tracePath() != null
                ? Path.of(cli.tracePath())
                : Path.of(required(env.traceDir(), "TRACE_DIR") + "/" + stamp + ".jsonl");

        // LightRAG：URL 未配置 = 检索停用（core 语义：null → 工具不注册）；超时为调优参数（默认 8s）
        String lightRagUrl = env.lightRagUrl();
        LightRagSettings lightRag = isBlank(lightRagUrl) ? null
                : new LightRagSettings(lightRagUrl, env.lightRagApiKey(),
                        orDefault(env.lightRagTimeoutMs(), 8_000L));

        // 实况播报：空/0 = 关，其余 = 输出文件路径（原 "1" 简写已取消）
        String battleReport = env.battleReport();
        battleReport = isBlank(battleReport) || "0".equals(battleReport.trim()) ? null : battleReport.trim();

        // 运行调参：默认值在 DuelTuning.defaults()，env 未配置的键继承默认
        DuelTuning base = DuelTuning.defaults();
        DuelTuning tuning = new DuelTuning(
                Boolean.TRUE.equals(env.duelRawFrames()),
                orDefault(env.duelChoiceTimeoutMs(), base.choiceTimeoutMillis()),
                orDefault(env.duelStartWaitMs(), base.startWaitMillis()),
                orDefault(env.comboContextLimit(), base.comboContextLimit()));

        return new RuntimeConfig(host, port, name, cli.ydkPath(), move, cli.archetype(),
                duelLog, trace, lightRag, battleReport,
                deckStrategy(env), tuning, planner(env),
                VerboseLogSettings.parse(env.verboseLog(), env.verboseMaxChars()),
                RunnerConfig.fromEnv(env));
    }

    /** 卡组思路：优先读文件（DECK_STRATEGY_FILE），其次内联文本（DECK_STRATEGY）。 */
    private static String deckStrategy(EnvSettings env) {
        String file = env.deckStrategyFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (Exception e) {
                log.warn("读取 DECK_STRATEGY_FILE 失败: {}", e.toString());
            }
        }
        return env.deckStrategy();
    }

    /**
     * v6 规划器/执行器设置：未显式开启且 LLM_API_KEY 未配置 → null（走 Reflex 快路径）；
     * 显式开启（PLAN_EXEC_ENABLED=true）但缺 key → 抛点名。环境相关项（地址/模型/卡库路径）必填，
     * 调优旋钮默认值在 {@link PipelineSettings#defaults}，env 未配置的键继承默认。
     */
    static PipelineSettings planner(EnvSettings env) {
        Boolean enabled = env.planExecEnabled();
        String apiKey = env.llmApiKey();
        boolean explicitOn = Boolean.TRUE.equals(enabled);
        if (!explicitOn && (Boolean.FALSE.equals(enabled) || isBlank(apiKey))) {
            return null;
        }
        if (explicitOn && isBlank(apiKey)) {
            throw new IllegalStateException("PLAN_EXEC_ENABLED=true 但 LLM_API_KEY 未配置："
                    + "填入网关凭据，或去掉显式开启（缺席 = 有 key 才启用）");
        }
        String model = firstNonBlank(env.plannerModel(), env.llmModel());
        PipelineSettings defaults = PipelineSettings.defaults(
                Path.of(required(env.cardRagPath(), "CARD_RAG_PATH")),
                required(env.llmBaseUrl(), "LLM_BASE_URL"),
                apiKey,
                required(model, "PLANNER_MODEL / LLM_MODEL"));
        return PipelineSettings.builder(defaults)
                .reasoningPlanner(env.reasoningPlanner())
                .reasoningDecision(env.reasoningDecision())
                .waitPerDecisionMs(env.plannerWaitDecisionMs())
                .firstJudgeWaitMs(env.plannerFirstWaitMs())
                .waitPerTurnMs(env.plannerWaitTurnMs())
                .maxCheckpointsPerTurn(env.plannerMaxCheckpoints())
                .similarityThreshold(env.plannerSimilarityThreshold())
                .judgeTimeoutMs(env.judgeTimeoutMs())
                .plannerCallTimeoutMs(env.plannerCallTimeoutMs())
                .plannerCooldownMs(env.plannerCooldownMs())
                .judgeMaxTokens(env.judgeMaxTokens())
                .pathPlanning(env.plannerPathPlanning())
                .plannerRushAnswer(env.plannerRushAnswer())
                .build();
    }

    // ---- 环境相关必填校验（调优参数有代码默认，不走此路径）----

    private static String required(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalStateException("缺少必填配置 " + key
                    + "（复制 .env.example 为 .env 并填写，或以 OS env 提供）");
        }
        return value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
