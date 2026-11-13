package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.support.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * CLI 的配置对象（组装根 {@link DuelRunnerMain} 从 OS env + .env 一次性解析，此后全走本对象）。
 * server 不用这里：server 由 Spring 的 @ConfigurationProperties 直接绑定（见 ygo-play-server/config）。
 *
 * @param host         YGOPro 服务地址（默认 127.0.0.1）
 * @param port         YGOPro 服务端口（默认 7911）
 * @param name         房间内玩家名（默认 ygo-play-runner）
 * @param ydkPath      卡组 .ydk 文件路径（与粘贴文本二选一）
 * @param move         先后手：first / second（默认后手）
 * @param archetype    卡组主题（combo 注入与检索的键；可空）
 * @param duelLogPath  对局记录 JSONL 落盘路径
 * @param tracePath    决策轨迹 JSONL 落盘路径
 * @param lightRag     LightRAG 知识栈连接（规则/历史对局检索）
 * @param battleReport 实况战场播报输出目标；null/0 = 关闭，"1" = /tmp/ygo-live/battlefield.log，其余 = 文件路径
 * @param deckStrategy 用户手写的卡组打法思路（注入规划器/决策器上下文；可空）
 * @param tuning       一局对局的运行调参（原始帧/看门狗/进房等待/combo 注入上限）
 * @param planner      v6 规划器/执行器设置；null = 未启用（走 Reflex/Tactical 快路径）
 */
public record RuntimeConfig(
        String host, int port, String name, String ydkPath, String move,
        String archetype,
        Path duelLogPath, Path tracePath,
        LightRagEndpoint lightRag,
        String battleReport,
        String deckStrategy,
        DuelTuning tuning,
        PipelineSettings planner) {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static RuntimeConfig resolve(CliOptions cli) {
        String host = cli.host() != null ? cli.host() : "127.0.0.1";
        int port = cli.port() != null ? cli.port() : 7911;
        String name = cli.name() != null ? cli.name() : "ygo-play-runner";
        String move = cli.move() != null ? cli.move() : "second";
        String envPath = cli.envPath() != null ? cli.envPath() : ".env";

        Map<String, String> dotenv = DotEnv.load(Path.of(envPath));
        Function<String, String> lookup = setting(dotenv);

        String stamp = LocalDateTime.now().format(TS);
        Path duelLog = cli.duelLogPath() != null
                ? Path.of(cli.duelLogPath()) : Path.of("/tmp/ygo-duel-log/" + stamp + ".jsonl");
        Path trace = cli.tracePath() != null
                ? Path.of(cli.tracePath()) : Path.of("/tmp/ygo-trace/" + stamp + ".jsonl");

        LightRagEndpoint lightRag = new LightRagEndpoint(
                lookup.apply("LIGHTRAG_URL"),
                lookup.apply("LIGHTRAG_API_KEY"),
                parseLong(lookup.apply("LIGHTRAG_TIMEOUT_MS"), 8_000L));
        return new RuntimeConfig(host, port, name, cli.ydkPath(), move, cli.archetype(),
                duelLog, trace, lightRag,
                lookup.apply("BATTLE_REPORT"),
                deckStrategy(lookup),
                tuning(lookup),
                planner(lookup).orElse(null));
    }

    /** OS env 优先、.env 并入的合并查表。 */
    private static Function<String, String> setting(Map<String, String> dotenv) {
        return key -> Texts.firstNonBlank(System.getenv(key), dotenv.get(key));
    }

    /** 卡组思路：优先读文件（DECK_STRATEGY_FILE），其次内联文本（DECK_STRATEGY）。 */
    private static String deckStrategy(Function<String, String> lookup) {
        String file = lookup.apply("DECK_STRATEGY_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (Exception e) {
                log.warn("读取 DECK_STRATEGY_FILE 失败: {}", e.toString());
            }
        }
        return lookup.apply("DECK_STRATEGY");
    }

    private static DuelTuning tuning(Function<String, String> lookup) {
        return new DuelTuning(
                truthy(lookup.apply("DUEL_RAW_FRAMES")),
                parseLong(lookup.apply("DUEL_CHOICE_TIMEOUT_MS"), 25_000L),
                parseLong(lookup.apply("DUEL_START_WAIT_MS"), 180_000L),
                (int) parseLong(lookup.apply("COMBO_CONTEXT_LIMIT"), 10L));
    }

    /**
     * v6 规划器/执行器设置：覆盖值与 core 默认级联；api-key 为空或显式 false → 未启用。
     * 数值/布尔解析容错（实机调参场景，写错一个值不该让对局起不来）。
     */
    static Optional<PipelineSettings> planner(Function<String, String> lookup) {
        Boolean enabled = triBool(lookup.apply("PLAN_EXEC_ENABLED"));
        String apiKey = lookup.apply("LLM_API_KEY");
        if (Boolean.FALSE.equals(enabled) || apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String baseUrl = Texts.firstNonBlank(lookup.apply("LLM_BASE_URL"), "https://api.deepseek.com");
        String model = Texts.firstNonBlank(lookup.apply("PLANNER_MODEL"),
                lookup.apply("LLM_MODEL"), "deepseek-chat");
        PipelineSettings defaults = PipelineSettings.defaults(
                Path.of(Texts.firstNonBlank(lookup.apply("CARD_RAG_PATH"), "data/card_rag/cards.jsonl")),
                baseUrl, apiKey, model);
        return Optional.of(new PipelineSettings(
                true,
                defaults.baseUrl(),
                defaults.apiKey(),
                defaults.model(),
                lookup.apply("LLM_REASONING_PLANNER"),
                lookup.apply("LLM_REASONING_DECISION"),
                intOf(lookup.apply("PLANNER_WAIT_DECISION_MS"), defaults.waitPerDecisionMs()),
                intOf(lookup.apply("PLANNER_FIRST_WAIT_MS"), defaults.firstJudgeWaitMs()),
                intOf(lookup.apply("PLANNER_WAIT_TURN_MS"), defaults.waitPerTurnMs()),
                intOf(lookup.apply("PLANNER_MAX_CHECKPOINTS"), defaults.maxCheckpointsPerTurn()),
                doubleOf(lookup.apply("PLANNER_SIMILARITY_THRESHOLD"), defaults.similarityThreshold()),
                intOf(lookup.apply("JUDGE_TIMEOUT_MS"), defaults.judgeTimeoutMs()),
                defaults.plannerCallTimeoutMs(),
                defaults.plannerCooldownMs(),
                defaults.cardRagPath(),
                defaults.judgeMaxTokens(),
                truthy(lookup.apply("PLANNER_PATH_PLANNING")),
                rushAnswer(lookup.apply("PLANNER_RUSH_ANSWER"), defaults.plannerRushAnswer())));
    }

    /** 三态布尔：true/1/on/yes → true，false/0/off/no → false，其余（含 null/空白/乱写）→ 未配置。 */
    private static Boolean triBool(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        return v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("no")
                ? Boolean.FALSE
                : null;
    }

    /** 两态布尔：键存在时，非真值一律 false（与旧 rush-answer 语义一致）。 */
    private static boolean rushAnswer(String raw, boolean fallback) {
        return raw == null ? fallback : rushTrue(raw);
    }

    private static boolean rushTrue(String raw) {
        String v = raw.trim();
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes");
    }

    private static boolean truthy(String raw) {
        return raw != null && rushTrue(raw);
    }

    private static int intOf(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
