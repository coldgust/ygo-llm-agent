package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全链路日志（可关）。由组装根构造并注入使用方（chat 传输层、规划器、流水、轨迹落日志），core 不读环境变量：
 * CLI 从 env+dotenv 经 {@link VerboseLogSettings#parse} 解析，server 从 Spring 配置绑定后构造。
 */
public final class VerboseLog {

    private static final Logger log =
            LoggerFactory.getLogger("ygo.llm");

    private final AtomicLong seq = new AtomicLong();
    private final boolean enabled;
    private final int maxChars;
    private final ThreadLocal<String> current = new ThreadLocal<>();

    public VerboseLog(VerboseLogSettings settings) {
        this.enabled = settings.enabled();
        this.maxChars = settings.maxChars();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 标注当前线程这次调用是谁发起的（规划器 checkpoint / 决策器判断 / 赛后复盘）。
     * 用 try-with-resources 包住调用即可，异常安全。
     */
    public Scope scope(String label) {
        if (!enabled || label == null || label.isBlank()) {
            return Scope.NOOP;
        }
        return new Scope(label, current.get(), true, current).open();
    }

    /** 一次调用的编号（工具调用与请求/响应共用）。 */
    public long nextId() {
        return seq.incrementAndGet();
    }

    public void request(long id, String model, ChatOptions options, List<ChatMessage> messages) {
        if (!enabled) {
            return;
        }
        String who = label();
        int total = messages.stream().mapToInt(VerboseLog::messageChars).sum();
        log.info("[LLM→] #{} {}model={} temp={} max_tokens={} json={} reasoning={} timeout={}ms 消息 {} 条/共 {} 字",
                id, who, model,
                options == null ? "-" : options.temperature(),
                options == null ? "-" : options.maxTokens(),
                options != null && options.jsonMode(),
                options == null || options.reasoning() == null ? "-" : options.reasoning(),
                options == null ? "-" : options.timeout().toMillis(),
                messages.size(), total);
        for (ChatMessage message : messages) {
            String text = messageText(message);
            log.info("[LLM→] #{} ── {} ({}) ──\n{}", id, message.type(),
                    len(text), clip(text));
        }
    }

    private static int messageChars(ChatMessage message) {
        return messageText(message).length();
    }

    /** 框架消息类型的文本视图（工具结果也按其 text 记录）。 */
    public static String messageText(ChatMessage message) {
        if (message instanceof SystemMessage s) {
            return s.text();
        }
        if (message instanceof UserMessage u) {
            return u.singleText();
        }
        if (message instanceof AiMessage a) {
            return a.text() == null ? "" : a.text();
        }
        if (message instanceof ToolExecutionResultMessage t) {
            return t.text();
        }
        return "";
    }

    /** 响应：耗时 + 正文（含"空正文"这种要命的情况）。 */
    public void response(long id, long elapsedMs, String content, String finishReason) {
        if (!enabled) {
            return;
        }
        String body = content == null ? "" : content;
        log.info("[LLM←] #{} {} {}ms {}字 finish={}\n{}", id, label().strip(), elapsedMs, body.length(),
                finishReason == null || finishReason.isBlank() ? "-" : finishReason,
                body.isBlank() ? "（空正文）" : clip(body));
    }

    /** 失败：把根因写清楚，别只留一句"调用失败"。 */
    public void error(long id, long elapsedMs, String message) {
        if (!enabled) {
            return;
        }
        log.warn("[LLM✗] #{} {} {}ms {}", id, label().strip(), elapsedMs, message);
    }

    public void toolCall(long id, String name, String arguments) {
        if (!enabled) {
            return;
        }
        log.info("[TOOL→] #{} {} {}({})", id, label().strip(), name, clip(arguments));
    }

    /** 工具调用的返回（耗时 + 结果；结果往往很长，这里按上限截断）。 */
    public void toolResult(long id, String name, long elapsedMs, String result) {
        if (!enabled) {
            return;
        }
        log.info("[TOOL←] #{} {} {}ms {}字\n{}", id, name, elapsedMs, len(result), clip(result));
    }

    /**
     * 决策流水：一级一级走过来了什么、为什么落到下一级。
     * 与 trace JSONL 的区别是"人能直接读"，不用再开文件对照。
     */
    public void decision(String stage, String detail) {
        if (!enabled) {
            return;
        }
        LoggerFactory.getLogger("ygo.decision")
                .info("[决策] {}{} {}", stage, detail == null || detail.isBlank() ? "" : "｜", detail);
    }

    /** 交互式步骤（等计划、等模型、提交等），用来判断"时间花在哪"。 */
    public void step(String what, long elapsedMs, String detail) {
        if (!enabled) {
            return;
        }
        LoggerFactory.getLogger("ygo.decision")
                .info("[耗时] {} {}ms {}", what, elapsedMs, detail == null ? "" : detail);
    }

    public String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private String label() {
        String label = current.get();
        return label == null ? "" : "[" + label + "] ";
    }

    private int len(String text) {
        return text == null ? 0 : text.length();
    }

    /** 按 {@code YGO_VERBOSE_MAX_CHARS} 截断；0/负数 = 不截断。 */
    private String clip(String text) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…（已截断，共 " + text.length() + " 字）";
    }

    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null, null, false, null);

        private final String label;
        private final String previous;
        private final boolean active;
        private final ThreadLocal<String> current;

        private Scope(String label, String previous, boolean active, ThreadLocal<String> current) {
            this.label = label;
            this.previous = previous;
            this.active = active;
            this.current = current;
        }

        Scope open() {
            if (active) {
                current.set(label);
            }
            return this;
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }
}
