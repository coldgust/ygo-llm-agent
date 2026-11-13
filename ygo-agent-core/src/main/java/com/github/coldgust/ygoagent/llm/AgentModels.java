package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

import java.time.Duration;
import java.util.List;

/**
 * agent 模型构建与 AiServices 组装的共享工具：档位 bake 进模型
 * （{@code defaultRequestParameters}），json/tools 配对在 bake 时内化
 * （注册了工具的模型不带 json_object，实测见 pitfalls §7），重试降级装饰器包在真实传输外。
 */
public final class AgentModels {

    public static final int MAX_TOOL_ROUNDS = 6;

    private AgentModels() {
    }

    /** OpenAI 兼容模型 + 重试降级装饰器；档位参数 bake 进模型。 */
    public static ChatModel openAi(LlmEndpoint endpoint, ChatOptions tier, Duration timeout,
                                   boolean withTools, VerboseLog trace) {
        return new ResilientChatModel(OpenAiChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .timeout(timeout)
                .maxRetries(0)
                .defaultRequestParameters(TierParameters.of(tier, endpoint.model(), withTools))
                .listeners(traceListener(trace))
                .build());
    }

    /** 工具埋点与错误回填（框架钩子）；带工具变体注册工具并限轮次。 */
    public static <T> AiServices<T> hooks(VerboseLog trace, AiServices<T> builder,
                                          List<Object> tools) {
        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools).maxToolCallingRoundTrips(MAX_TOOL_ROUNDS);
        }
        return builder
                .beforeToolExecution(before -> trace.toolCall(trace.nextId(),
                        before.request().name(), before.request().arguments()))
                .afterToolExecution(after -> trace.toolResult(trace.nextId(),
                        after.request().name(),
                        java.time.Duration.between(after.startTime(), after.finishTime()).toMillis(),
                        String.valueOf(after.result())))
                .toolExecutionErrorHandler((e, ctx) -> ToolErrorHandlerResult.text(
                        // 检索失败也要把结果回填，让模型基于已有上下文继续，而不是整轮报废
                        "（工具检索失败：" + clip(e.getMessage())
                                + "。请基于上下文里已有的信息继续判断）"));
    }

    /**
     * 请求/响应/错误的统一埋点：每次 HTTP 调用一条，消息原文交给 trace.request。
     * 同步调用在发起线程上触发，scope 标签（VerboseLog.scope，实例注入）因此可用。
     */
    private static ChatModelListener traceListener(VerboseLog trace) {
        ThreadLocal<long[]> context = new ThreadLocal<>();
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                ChatRequest request = ctx.chatRequest();
                long id = trace.nextId();
                context.set(new long[]{id, System.nanoTime()});
                trace.request(id, modelName(request), optionsView(request), request.messages());
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                long[] state = context.get();
                context.remove();
                trace.response(state == null ? trace.nextId() : state[0],
                        elapsed(state), textOf(ctx.chatResponse().aiMessage()),
                        ctx.chatResponse().finishReason() == null
                                ? "" : ctx.chatResponse().finishReason().name());
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {
                long[] state = context.get();
                context.remove();
                trace.error(state == null ? trace.nextId() : state[0], elapsed(state),
                        String.valueOf(ctx.error()));
            }

            private long elapsed(long[] state) {
                return state == null ? 0 : (System.nanoTime() - state[1]) / 1_000_000;
            }
        };
    }

    /** 从请求参数还原档位概要，供 trace 头部展示。 */
    private static ChatOptions optionsView(ChatRequest request) {
        OpenAiChatRequestParameters p = request.parameters() instanceof OpenAiChatRequestParameters o
                ? o : null;
        if (p == null) {
            return null;
        }
        return new ChatOptions(
                p.temperature() == null ? 0 : p.temperature(),
                p.maxOutputTokens() == null ? 0 : p.maxOutputTokens(),
                p.responseFormat() != null,
                p.reasoningEffort());
    }

    private static String modelName(ChatRequest request) {
        return request.parameters() == null || request.parameters().modelName() == null
                ? "?" : request.parameters().modelName();
    }

    private static String textOf(dev.langchain4j.data.message.AiMessage ai) {
        return ai == null || ai.text() == null ? "" : ai.text();
    }

    private static String clip(String text) {
        String s = text == null ? "null" : text;
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
