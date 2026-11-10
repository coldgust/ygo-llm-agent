package com.github.coldgust.ygoagent.decision.planexec.llm;

import com.github.coldgust.ygoagent.decision.planexec.Texts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则书检索工具：把"查规则"这件事做成模型可自主调用的函数（OpenAI tools / function calling）。
 */
public final class RulebookTool {

    public static final String NAME = "search_rulebook";

    private static final Logger log = LoggerFactory.getLogger(RulebookTool.class);
    private static final int MAX_RESULT_CHARS = 3_000;

    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String mode;
    private final int defaultTopK;

    public RulebookTool(String baseUrl, String apiKey, Duration timeout, String mode, int defaultTopK) {
        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost:9621" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/query/data";
        this.apiKey = apiKey;
        this.timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
        this.mode = mode == null || mode.isBlank() ? "mix" : mode;
        this.defaultTopK = defaultTopK <= 0 ? 6 : defaultTopK;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 从配置构建：优先进程环境，其次当前/上级目录的 {@code .env}。
     */
    public static RulebookTool fromEnv() {
        return new RulebookTool(
                setting("LIGHTRAG_URL", "http://localhost:9621"),
                setting("LIGHTRAG_API_KEY", ""),
                Duration.ofMillis(settingMillis("LIGHTRAG_TIMEOUT_MS", 8_000)),
                "mix",
                8); // 召回放大一点：域过滤会丢掉非规则片段
    }

    /**
     * 单 workspace 多域共存时的检索侧隔离：规则工具只接受规则域来源。
     */
    static boolean isRuleDomain(String source) {
        // 域名判定集中在一处，`rule:` 与 `rule-`（upload 拒绝冒号）等价
        return SourceDomain.isRule(source);
    }

    private static String setting(String key, String fallback) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        for (String path : new String[]{".env", "../.env", "../../.env"}) {
            String value = readDotEnv(path, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static long settingMillis(String key, long fallback) {
        try {
            return Math.max(1_000L, Long.parseLong(setting(key, String.valueOf(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readDotEnv(String path, String key) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.startsWith("#") || !line.startsWith(key + "=")) {
                    continue;
                }
                String value = line.substring(key.length() + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException e) {
            log.debug("读取 {} 失败: {}", path, e.toString());
        }
        return null;
    }

    public List<ChatClient.Tool> tools() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "要检索的规则问题或关键词（中英文均可），例如：额外怪兽区 连接怪兽 放置、"
                + "连锁 咒文速度 反击陷阱、效果被无效 是否破坏");

        Map<String, Object> topK = new LinkedHashMap<>();
        topK.put("type", "integer");
        topK.put("description", "返回片段数，默认 6，最多 12");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", query);
        props.put("top_k", topK);
        params.put("properties", props);
        params.put("required", List.of("query"));

        return List.of(new ChatClient.Tool(NAME,
                "查阅游戏王官方规则书（大师规则 v10）原文。适用于：具体裁定、召唤/连锁/战斗的精确条件、"
                        + "卡种与区域规则、不熟悉的判定。上下文的[规则速查]已覆盖常识，"
                        + "只有需要精确条文或遇到拿不准的判定时才调用。返回相关规则片段与来源。",
                params));
    }

    public ChatClient.ToolHandler handler() {
        return call -> retrieve(call.arguments());
    }

    /** 执行一次检索；参数不合法/服务不可用时抛异常，由 ChatClient 回填错误让模型继续。 */
    String retrieve(String argumentsJson) {
        String query = "";
        int topK = defaultTopK;
        try {
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                JsonNode args = mapper.readTree(argumentsJson);
                query = args.path("query").asText("");
                if (args.path("top_k").isNumber()) {
                    topK = Math.max(1, Math.min(12, args.path("top_k").asInt(defaultTopK)));
                }
            }
        } catch (RuntimeException e) {
            query = argumentsJson == null ? "" : argumentsJson; // 参数不是 JSON：把原文当查询词
        }
        if (query.isBlank()) {
            return "（查询为空：请提供要查阅的规则问题或关键词）";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("mode", mode);
        payload.put("top_k", topK);
        payload.put("chunk_top_k", topK);
        payload.put("include_chunk_content", true);

        String text = render(post(payload));
        log.info("规则检索 query='{}' topK={} → {} 字", Texts.flattenClip(query, 40), topK, text.length());
        return text;
    }

    private JsonNode post(Map<String, Object> payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("LightRAG HTTP " + response.statusCode() + ": "
                        + Texts.flattenClip(response.body(), 200));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("规则检索 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("规则检索被中断", e);
        }
    }

    private String render(JsonNode root) {
        JsonNode data = root.path("data");
        StringBuilder sb = new StringBuilder();
        int total = 0;

        JsonNode chunks = data.path("chunks");
        if (chunks.isArray()) {
            for (JsonNode chunk : chunks) {
                String content = chunk.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                String source = Texts.firstNonBlank(chunk, "file_path", "id");
                if (!isRuleDomain(source)) {
                    continue; // 单 workspace 多域共存：只吃规则域，避免卡牌/combo/对局片段串味
                }
                sb.append("[规则片段").append(++total).append("]");
                if (!source.isBlank()) {
                    sb.append("（").append(Texts.flattenClip(source, 60)).append("）");
                }
                sb.append("\n").append(Texts.flattenClip(content, 1_200)).append("\n\n");
                if (sb.length() > MAX_RESULT_CHARS) {
                    break;
                }
            }
        }

        if (total == 0) {
            JsonNode entities = data.path("entities");
            if (entities.isArray()) {
                for (JsonNode e : entities) {
                    String desc = e.path("description").asText("");
                    if (desc.isBlank()) {
                        continue;
                    }
                    String name = Texts.firstNonBlank(e, "entity_name", "name");
                    sb.append("- ").append(Texts.flattenClip(name, 40)).append("：")
                            .append(Texts.flattenClip(desc, 300)).append("\n");
                    if (++total >= 8) {
                        break;
                    }
                }
            }
        }

        if (total == 0) {
            return "（规则书未命中相关条文；请基于[规则速查]与卡面信息判断）";
        }
        String out = sb.toString();
        return out.length() <= MAX_RESULT_CHARS ? out : out.substring(0, MAX_RESULT_CHARS) + "…";
    }
}
