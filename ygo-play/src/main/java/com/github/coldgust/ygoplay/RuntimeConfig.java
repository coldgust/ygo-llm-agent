package com.github.coldgust.ygoplay;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public record RuntimeConfig(
        String host, int port, String name, String ydkPath, String move,
        String archetype,
        Path duelLogPath, Path tracePath,
        Llm llm) {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Llm(String model, String baseUrl, String apiKey) {

        @Override
        public String toString() {
            return "Llm[model=" + model + ", baseUrl=" + baseUrl
                    + ", apiKey=" + (apiKey == null ? "null" : "***") + "]";
        }
    }

    public static RuntimeConfig resolve(CliOptions cli) {
        String host = cli.host() != null ? cli.host() : "127.0.0.1";
        int port = cli.port() != null ? cli.port() : 7911;
        String name = cli.name() != null ? cli.name() : "ygo-play-runner";
        String move = cli.move() != null ? cli.move() : "second";
        String envPath = cli.envPath() != null ? cli.envPath() : ".env";

        Map<String, String> dotenv = DotEnv.load(Path.of(envPath));

        String stamp = LocalDateTime.now().format(TS);
        Path duelLog = cli.duelLogPath() != null
                ? Path.of(cli.duelLogPath()) : Path.of("/tmp/ygo-duel-log/" + stamp + ".jsonl");
        Path trace = cli.tracePath() != null
                ? Path.of(cli.tracePath()) : Path.of("/tmp/ygo-trace/" + stamp + ".jsonl");

        Llm llm = resolveLlm(dotenv);
        return new RuntimeConfig(host, port, name, cli.ydkPath(), move, cli.archetype(),
                duelLog, trace, llm);
    }

    private static Llm resolveLlm(Map<String, String> dotenv) {
        String apiKey = firstNonBlank(System.getenv("LLM_API_KEY"), dotenv.get("LLM_API_KEY"));
        String baseUrl = firstNonBlank(System.getenv("LLM_BASE_URL"), dotenv.get("LLM_BASE_URL"),
                "https://api.deepseek.com");
        String model = firstNonBlank(System.getenv("LLM_MODEL"), dotenv.get("LLM_MODEL"), "deepseek-chat");
        return new Llm(model, baseUrl, apiKey);
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
