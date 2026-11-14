package com.github.coldgust.ygoplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI resolve 严格装配：env 承载默认值、CLI 参数覆盖、必填缺失 → 抛异常点名。 */
class RuntimeConfigTest {

    @TempDir
    Path tmp;

    /** .env 最小集（仅环境相关必填键；无 LLM_API_KEY → planner 关闭；调优键缺省 = 代码默认）。 */
    private static final String BASE_ENV = """
            YGO_HOST=192.168.1.9
            DUEL_LOG_DIR=/tmp/duel
            TRACE_DIR=/tmp/trace
            """;

    private Path writeEnv(String content) throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, content);
        return env;
    }

    private static CliOptions cli(Path env) {
        return new CliOptions(null, null, null, null, null, null, null, null, env.toString());
    }

    @Test
    void envValuesFlowIntoConfig() throws Exception {
        Path env = writeEnv(BASE_ENV + """
                LIGHTRAG_URL=http://rag.local:9621
                LIGHTRAG_TIMEOUT_MS=5000
                """);

        RuntimeConfig cfg = RuntimeConfig.resolve(
                new CliOptions(null, null, null, null, null, null, null, "SKY_STRIKER", env.toString()));

        assertEquals("192.168.1.9", cfg.host(), "YGO_HOST 承载连接默认（环境相关必填）");
        assertEquals(7_911, cfg.port(), "YGO_PORT 未配置 = 代码默认 7911");
        assertEquals("ygo-play-runner", cfg.name(), "YGO_PLAYER_NAME 未配置 = 代码默认");
        assertEquals("second", cfg.move(), "YGO_MOVE 未配置 = 代码默认");
        assertEquals("SKY_STRIKER", cfg.archetype());
        assertTrue(cfg.duelLogPath().startsWith(Path.of("/tmp/duel")), "DUEL_LOG_DIR 承载归档目录");
        assertEquals(25_000L, cfg.tuning().choiceTimeoutMillis(), "调优键未配置 = DuelTuning.defaults");
        assertEquals("http://rag.local:9621", cfg.lightRag().url());
        assertEquals(5_000L, cfg.lightRag().timeoutMillis(), ".env 的超时并入 LightRAG 连接");
        assertFalse(cfg.verbose().enabled(), "verbose 键未配置 = 默认关");
        assertNull(cfg.planner(), "无 LLM_API_KEY = planner 关闭");
    }

    @Test
    void lightragUrlAbsentMeansRetrievalOff() throws Exception {
        RuntimeConfig cfg = RuntimeConfig.resolve(cli(writeEnv(BASE_ENV)));
        assertNull(cfg.lightRag(), "LIGHTRAG_URL 未配置 = 检索停用");
    }

    @Test
    void missingRequiredKeyThrowsNamingKey() throws Exception {
        Path env = writeEnv(BASE_ENV.replace("YGO_HOST=192.168.1.9\n", ""));

        Exception e = assertThrows(IllegalStateException.class, () -> RuntimeConfig.resolve(cli(env)));
        assertTrue(e.getMessage().contains("YGO_HOST"), e.getMessage());
    }

    @Test
    void cliArgOverridesEnv() throws Exception {
        RuntimeConfig cfg = RuntimeConfig.resolve(new CliOptions("10.0.0.5", 9999, null, null, null,
                null, null, null, writeEnv(BASE_ENV).toString()));
        assertEquals("10.0.0.5", cfg.host(), "CLI 参数覆盖 YGO_HOST");
        assertEquals(9999, cfg.port());
    }
}
