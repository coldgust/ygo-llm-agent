package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * 重试与降级策略，包在真实模型外面。三条实测语义（详见 pitfalls）：
 * 超时不重试（45s 超时 + 重试 = 59.8s，直接吃光回合预算）；
 * 空正文放宽 max_tokens 重试一次（推理型模型把额度烧在思维链上）；
 * 400 带思考参数时去掉参数重试一次（端点可能不认）。其他抖动原样再补一次。
 */
public final class ResilientChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    private final ChatModel delegate;
    /** 委托模型的默认档位参数（defaultRequestParameters）；重试重建与空正文判定都要看得到它们。 */
    private final OpenAiChatRequestParameters defaults;

    public ResilientChatModel(ChatModel delegate) {
        this.delegate = delegate;
        this.defaults = delegate.defaultRequestParameters()
                instanceof OpenAiChatRequestParameters p ? p : null;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatRequest effective = effective(request);
        ChatResponse response = attempt(effective);
        if (isBlank(response)) {
            OpenAiChatRequestParameters params = openAiParams(request);
            if (params == null || params.maxOutputTokens() == null) {
                return response;
            }
            // 空响应等于这一轮白跑，放宽 token 上限再试一次（clamp 2000–8000，避免失控）
            int widened = Math.min(Math.max(params.maxOutputTokens() * 2, 2_000), 8_000);
            log.warn("LLM 返回空内容（max_tokens={}），放宽到 {} 重试一次",
                    params.maxOutputTokens(), widened);
            return delegate.chat(rebuild(effective, withMaxTokens(params, widened)));
        }
        return response;
    }

    /**
     * 模型级 defaultRequestParameters 要到 OpenAiChatModel 内部才合并，装饰器看不到——
     * 用框架的 {@code defaultedBy} 在这里提前合并，空正文判定与重试重建才能拿到档位值。
     */
    private ChatRequest effective(ChatRequest request) {
        if (defaults == null) {
            return request;
        }
        OpenAiChatRequestParameters p = openAiParams(request);
        if (p == null) {
            return rebuild(request, defaults);
        }
        return p.defaultedBy(defaults) instanceof OpenAiChatRequestParameters merged
                ? rebuild(request, merged) : request;
    }

    /**
     * 单次尝试；400 且带了思考参数 → 端点可能不认这些字段，去掉重试；其他抖动直接再补一次。
     */
    private ChatResponse attempt(ChatRequest request) {
        try {
            return delegate.chat(request);
        } catch (RuntimeException e) {
            LlmChatException mapped = translate(e);
            if (isTimeout(mapped)) {
                throw mapped;
            }
            if (isHttp400(mapped) && hasReasoning(request)) {
                log.warn("端点拒绝思考参数，改按默认模式重试: {}", truncate(mapped.getMessage()));
                try {
                    return delegate.chat(rebuild(request,
                            withoutReasoning(openAiParams(request))));
                } catch (RuntimeException retryError) {
                    throw translate(retryError);
                }
            }
            // 其他抖动（HTTP 5xx/网络）：原样再补一次
            try {
                return delegate.chat(request);
            } catch (RuntimeException retryError) {
                throw translate(retryError);
            }
        }
    }

    private static ChatRequest rebuild(ChatRequest request, OpenAiChatRequestParameters params) {
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(params)
                .build();
    }

    /** 放宽 maxOutputTokens 的重建（空正文放宽重试）；其余字段原样保留。 */
    private static OpenAiChatRequestParameters withMaxTokens(OpenAiChatRequestParameters p, int maxTokens) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder()
                .modelName(p.modelName())
                .temperature(p.temperature())
                .maxOutputTokens(maxTokens);
        if (p.customParameters() != null) {
            builder.customParameters(p.customParameters());
        }
        if (p.reasoningEffort() != null) {
            builder.reasoningEffort(p.reasoningEffort());
        }
        if (p.responseFormat() != null) {
            builder.responseFormat(p.responseFormat());
        }
        return builder.build();
    }

    /** 去掉思考参数的重建（400 丢思考重试）；其余字段原样保留。 */
    private static OpenAiChatRequestParameters withoutReasoning(OpenAiChatRequestParameters p) {
        java.util.Map<String, Object> custom = p.customParameters() == null
                ? java.util.Map.of() : new java.util.LinkedHashMap<>(p.customParameters());
        custom.remove("thinking");
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder()
                .modelName(p.modelName())
                .temperature(p.temperature())
                .maxOutputTokens(p.maxOutputTokens())
                .customParameters(custom);
        if (p.responseFormat() != null) {
            builder.responseFormat(p.responseFormat());
        }
        return builder.build();
    }

    static boolean isBlank(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return true;
        }
        AiMessage ai = response.aiMessage();
        if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
            return false; // 工具调用不是空正文
        }
        return ai.text() == null || ai.text().isBlank();
    }

    private static boolean hasReasoning(ChatRequest request) {
        OpenAiChatRequestParameters p = openAiParams(request);
        return p != null && (p.reasoningEffort() != null
                || (p.customParameters() != null && p.customParameters().containsKey("thinking")));
    }

    private static OpenAiChatRequestParameters openAiParams(ChatRequest request) {
        return request.parameters() instanceof OpenAiChatRequestParameters p ? p : null;
    }

    static boolean isTimeout(LlmChatException e) {
        if (e.getCause() instanceof HttpTimeoutException
                || e.getCause() instanceof dev.langchain4j.exception.TimeoutException) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("timed out") || msg.contains("timeout") || msg.contains("超时");
    }

    private static boolean isHttp400(LlmChatException e) {
        String msg = String.valueOf(e.getMessage());
        return msg.contains("HTTP 400");
    }

    /**
     * 统一转 {@link LlmChatException}，保留 HTTP 状态与超时语义（重试分类依赖文案）。
     */
    static LlmChatException translate(RuntimeException e) {
        if (e instanceof LlmChatException ce) {
            return ce;
        }
        if (e instanceof HttpException h) {
            return new LlmChatException("LLM HTTP " + h.statusCode() + ": "
                    + truncate(h.getMessage()), h);
        }
        if (e instanceof dev.langchain4j.exception.TimeoutException
                || e.getCause() instanceof HttpTimeoutException) {
            return new LlmChatException("LLM 调用超时: " + truncate(e.getMessage()), e);
        }
        return new LlmChatException("LLM 调用失败: " + truncate(e.getMessage()), e);
    }

    private static String truncate(String text) {
        return text == null ? "null" : text.substring(0, Math.min(300, text.length()));
    }
}
