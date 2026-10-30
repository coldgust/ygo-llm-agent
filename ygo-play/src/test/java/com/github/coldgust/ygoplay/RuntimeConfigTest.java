package com.github.coldgust.ygoplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeConfigTest {

    @Test
    void cliOverridesDotEnv(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "LLM_MODEL=whatever\n");

        RuntimeConfig cfg = RuntimeConfig.resolve(new CliOptions(null, null, null, null, null,
                null, null, "SKY_STRIKER", env.toString()));

        assertEquals("SKY_STRIKER", cfg.archetype());
        // LLM 凭据的优先级与解析属 PlanExecSettingsResolver（map 注入，可确定性地测）
        assertEquals("https://api.deepseek.com", cfg.llm().baseUrl());
    }
}
