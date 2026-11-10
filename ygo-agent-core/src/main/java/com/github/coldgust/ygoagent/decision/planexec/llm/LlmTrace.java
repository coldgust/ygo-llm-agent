package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全链路日志（可关）。
 */
public final class LlmTrace {

    private static final Logger log =
            LoggerFactory.getLogger("ygo.llm");

    private static final AtomicLong SEQ = new AtomicLong();

    private static final boolean ENABLED = resolveEnabled();
    private static final int MAX_CHARS = resolveMaxChars();

    private LlmTrace() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * 标注当前线程这次调用是谁发起的（规划器 checkpoint / 决策器判断 / 赛后复盘）。
     * 用 try-with-resources 包住调用即可，异常安全。
     */
    public static Scope scope(String label) {
        if (!ENABLED || label == null || label.isBlank()) {
            return Scope.NOOP;
        }
        return new Scope(label, CURRENT.get(), true).open();
    }

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null, null, false);

        private final String label;
        private final String previous;
        private final boolean active;

        private Scope(String label, String previous, boolean active) {
            this.label = label;
            this.previous = previous;
            this.active = active;
        }

        Scope open() {
            if (active) {
                CURRENT.set(label);
            }
            return this;
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 一次调用的编号（工具调用与请求/响应共用）。 */
    public static long nextId() {
        return SEQ.incrementAndGet();
    }

    public static void request(long id, String model, ChatClient.ChatOptions options, List<ChatClient.Msg> messages) {
        if (!ENABLED) {
            return;
        }
        String who = label();
        int total = messages.stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        log.info("[LLM→] #{} {}model={} temp={} max_tokens={} json={} reasoning={} timeout={}ms 消息 {} 条/共 {} 字",
                id, who, model,
                options == null ? "-" : options.temperature(),
                options == null ? "-" : options.maxTokens(),
                options != null && options.jsonMode(),
                options == null || options.reasoning() == null ? "-" : options.reasoning(),
                options == null ? "-" : options.timeout().toMillis(),
                messages.size(), total);
        for (ChatClient.Msg message : messages) {
            log.info("[LLM→] #{} ── {} ({}) ──\n{}", id, message.role(),
                    len(message.content()), clip(message.content()));
        }
    }

    /**
     * 工具轮请求：模型在这一轮能看到什么（含上一轮回填的工具结果）。
     * 工具轮与普通请求分开记，因为它的 body 去掉了 {@code response_format}、多带了 {@code tools}。
     */
    public static void toolRound(long id, String model, ChatClient.ChatOptions options,
                                 int round, int maxRounds, List<Map<String, Object>> wire,
                                 List<String> toolNames) {
        if (!ENABLED) {
            return;
        }
        log.info("[LLM→] #{} {}工具轮 {}/{} model={} temp={} max_tokens={} tools={} 消息 {} 条",
                id, label(), round, maxRounds, model,
                options == null ? "-" : options.temperature(),
                options == null ? "-" : options.maxTokens(),
                toolNames == null || toolNames.isEmpty() ? "（已收掉）" : toolNames,
                wire.size());
        for (Map<String, Object> message : wire) {
            String role = String.valueOf(message.get("role"));
            Object content = message.get("content");
            String text = content == null ? "" : String.valueOf(content);
            log.info("[LLM→] #{} ── {} ({}) ──\n{}", id, role, len(text), clip(text));
        }
    }

    /** 响应：耗时 + 正文（含"空正文"这种要命的情况）。 */
    public static void response(long id, long elapsedMs, String content, String finishReason) {
        if (!ENABLED) {
            return;
        }
        String body = content == null ? "" : content;
        log.info("[LLM←] #{} {} {}ms {}字 finish={}\n{}", id, label().strip(), elapsedMs, body.length(),
                finishReason == null || finishReason.isBlank() ? "-" : finishReason,
                body.isBlank() ? "（空正文）" : clip(body));
    }

    /** 失败：把根因写清楚，别只留一句"调用失败"。 */
    public static void error(long id, long elapsedMs, String message) {
        if (!ENABLED) {
            return;
        }
        log.warn("[LLM✗] #{} {} {}ms {}", id, label().strip(), elapsedMs, message);
    }

    public static void toolCall(long id, String name, String arguments) {
        if (!ENABLED) {
            return;
        }
        log.info("[TOOL→] #{} {} {}({})", id, label().strip(), name, clip(arguments));
    }

    /** 工具调用的返回（耗时 + 结果；结果往往很长，这里按上限截断）。 */
    public static void toolResult(long id, String name, long elapsedMs, String result) {
        if (!ENABLED) {
            return;
        }
        log.info("[TOOL←] #{} {} {}ms {}字\n{}", id, name, elapsedMs, len(result), clip(result));
    }

    /**
     * 决策流水：一级一级走过来了什么、为什么落到下一级。
     * 与 trace JSONL 的区别是"人能直接读"，不用再开文件对照。
     */
    public static void decision(String stage, String detail) {
        if (!ENABLED) {
            return;
        }
        LoggerFactory.getLogger("ygo.decision")
                .info("[决策] {}{} {}", stage, detail == null || detail.isBlank() ? "" : "｜", detail);
    }

    /** 交互式步骤（等计划、等模型、提交等），用来判断"时间花在哪"。 */
    public static void step(String what, long elapsedMs, String detail) {
        if (!ENABLED) {
            return;
        }
        LoggerFactory.getLogger("ygo.decision")
                .info("[耗时] {} {}ms {}", what, elapsedMs, detail == null ? "" : detail);
    }

    public static String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String label() {
        String label = CURRENT.get();
        return label == null ? "" : "[" + label + "] ";
    }

    private static int len(String text) {
        return text == null ? 0 : text.length();
    }

    /** 按 {@code YGO_VERBOSE_MAX_CHARS} 截断；0/负数 = 不截断。 */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (MAX_CHARS <= 0 || text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS) + "…（已截断，共 " + text.length() + " 字）";
    }

    private static boolean resolveEnabled() {
        String raw = setting("YGO_VERBOSE_LOG", null);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !"false".equals(v) && !"0".equals(v) && !"off".equals(v) && !"no".equals(v);
    }

    private static int resolveMaxChars() {
        try {
            String raw = setting("YGO_VERBOSE_MAX_CHARS", "0");
            return raw == null || raw.isBlank() ? 0 : Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 进程环境优先，其次当前/上级目录的 .env（服务端由 Spring 导入 .env，不进 OS 环境）。 */
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
            // 读不到就当没配
        }
        return null;
    }
}
