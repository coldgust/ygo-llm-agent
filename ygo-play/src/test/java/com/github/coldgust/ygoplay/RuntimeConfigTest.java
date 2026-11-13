package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeConfigTest {

    @Test
    void cliOverridesDotEnv(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "LIGHTRAG_TIMEOUT_MS=5000\n");

        RuntimeConfig cfg = RuntimeConfig.resolve(new CliOptions(null, null, null, null, null,
                null, null, "SKY_STRIKER", env.toString()));

        assertEquals("SKY_STRIKER", cfg.archetype());
        assertEquals("http://localhost:9621", cfg.lightRag().url(), "LIGHTRAG_URL 缺省取装配层默认");
        assertEquals(5_000L, cfg.lightRag().timeoutMillis(), ".env 的超时并入 LightRAG 连接");
    }

    @Test
    void plannerPlannerModelOverridesLlmModel() {
        Map<String, String> env = new HashMap<>();
        env.put("LLM_API_KEY", "k");
        env.put("LLM_MODEL", "deepseek-chat");
        env.put("PLANNER_MODEL", "deepseek-reasoner");

        PipelineSettings planner = RuntimeConfig.planner(env::get).orElseThrow();

        assertEquals("deepseek-reasoner", planner.model(),
                "PLANNER_MODEL 优先于 LLM_MODEL（决策热路径与叙事分模型）");
    }
}
