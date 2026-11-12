package com.github.coldgust.ygoagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.ChatMessage;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;

/**
 * 重试与降级策略，包在任意 transport 外面（现配 {@link LangChain4jChat}）。
 * <p>三条实测语义（详见 pitfalls）：超时不重试（45s 超时 + 重试 = 59.8s，直接吃光回合预算）；
 * 空正文放宽 max_tokens 重试一次（推理型模型把额度烧在思维链上）；400 带思考参数时
 * 去掉参数重试一次（端点可能不认）。工具回路失败一律降级为普通补全——检索不可用不拖垮决策。
 * <p>策略独立成类不依赖任何框架类型：transport 只管协议编解码，这里只管"失败了怎么办"。
 */
public final class ResilientChatClient implements ChatClient {

    private static final Logger log =
            LoggerFactory.getLogger(ResilientChatClient.class);

    private final ChatClient delegate;
    private final VerboseLog trace;

    public ResilientChatClient(ChatClient delegate, VerboseLog trace) {
        this.delegate = delegate;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
    }

    @Override
    public String complete(List<ChatMessage> messages, ChatOptions options) {
        String text = attempt(messages, options);
        if (text == null || text.isBlank()) {
            // 推理型模型可能把 max_tokens 全花在 reasoning 上，正文为空。
            // 空响应等于这一轮白跑，放宽 token 上限再试一次（上限 8000，避免失控）。
            ChatOptions wider = new ChatOptions(options.temperature(),
                    Math.min(Math.max(options.maxTokens() * 2, 2_000), 8_000),
                    options.jsonMode(), options.timeout(), options.reasoning());
            log.warn("LLM 返回空内容（max_tokens={}），放宽到 {} 重试一次",
                    options.maxTokens(), wider.maxTokens());
            text = attempt(messages, wider);
        }
        return text;
    }

    /** 单次尝试；400 且带了思考参数 → 端点可能不认这些字段，去掉重试；其他抖动直接再补一次。 */
    private String attempt(List<ChatMessage> messages, ChatOptions options) {
        try {
            return delegate.complete(messages, options);
        } catch (ChatException e) {
            if (isTimeout(e)) {
                throw e;
            }
            if (options.reasoning() != null && e.getMessage() != null
                    && e.getMessage().contains("HTTP 400")) {
                log.warn("端点拒绝思考参数，改按默认模式重试: {}", truncate(e.getMessage()));
                return delegate.complete(messages, options.withoutReasoning());
            }
            return delegate.complete(messages, options);
        }
    }

    @Override
    public String completeWithTools(List<ChatMessage> messages, ChatOptions options,
                                    List<Object> tools) {
        try {
            return delegate.completeWithTools(messages, options, tools);
        } catch (RuntimeException e) {
            // 检索/工具回路不可用不能拖垮决策；降级后的普通补全仍继承空响应放宽重试
            log.warn("带工具的调用失败，降级为普通补全: {}", truncate(e.getMessage()));
            trace.error(trace.nextId(), 0, "工具回路失败，降级为普通补全: " + e.getMessage());
            return complete(messages, options);
        }
    }

    static boolean isTimeout(ChatException e) {
        if (e.getCause() instanceof HttpTimeoutException
                || e.getCause() instanceof dev.langchain4j.exception.TimeoutException) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("timed out") || msg.contains("timeout") || msg.contains("超时");
    }

    private static String truncate(String text) {
        return text == null ? "null" : text.substring(0, Math.min(300, text.length()));
    }
}
