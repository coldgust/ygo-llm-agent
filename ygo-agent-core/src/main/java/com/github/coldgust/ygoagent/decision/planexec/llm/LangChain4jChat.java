package com.github.coldgust.ygoagent.decision.planexec.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OpenAI 兼容 /chat/completions 客户端，传输层基于 langchain4j（deepseek 等均兼容）。
 * <p>框架分工：工具声明与参数 schema 由 @Tool 注解提供（{@link ToolService#findTools} 提取）；
 * 请求/响应/错误埋点由 {@link ChatModelListener} 统一记录（每次 HTTP 调用一条）；
 * 本类只保留框架接管不了的部分——deepseek 私有参数映射、逐轮 json mode 控制、收尾兜底。
 * <p>deepseek 私有参数走两条通道：{@code reasoning_effort} 用框架字段，
 * {@code thinking} 开关用 {@code customParameters}（拍平进请求体顶层）。
 * <p>持续 session 的历史由调用方（PlannerSession）维护并整段重发——
 * deepseek 服务端对相同前缀自动做 context caching，无需客户端处理。
 */
public final class LangChain4jChat implements ChatClient {

    private static final Logger log =
            LoggerFactory.getLogger(LangChain4jChat.class);

    private static final int MAX_TOOL_ROUNDS = 6;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    /** langchain4j 的超时是客户端级配置，无法逐调用覆盖：按超时档缓存模型实例。 */
    private final ConcurrentMap<Duration, ChatModel> models = new ConcurrentHashMap<>();

    public LangChain4jChat(String baseUrl, String apiKey, String model) {
        String base = baseUrl == null ? "https://api.deepseek.com" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model;
    }

    @Override
    public String complete(List<ChatMessage> messages, ChatOptions options) {
        return textOf(chat(messages, parameters(options, null), options).aiMessage());
    }

    @Override
    public String completeWithTools(List<ChatMessage> messages, ChatOptions options,
                                    List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return complete(messages, options);
        }
        List<RegisteredTool> registered = register(tools);
        List<ToolSpecification> specs = registered.stream().map(RegisteredTool::spec).toList();
        List<String> toolNames = registered.stream().map(RegisteredTool::name).toList();
        long traceId = LlmTrace.nextId();
        List<ChatMessage> wire = new ArrayList<>(messages);
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            LlmTrace.toolRound(traceId, model, options, round, MAX_TOOL_ROUNDS, toolNames);
            AiMessage ai = chat(wire, parameters(options, specs), options).aiMessage();
            List<ToolExecutionRequest> calls = ai.toolExecutionRequests() == null
                    ? List.of() : ai.toolExecutionRequests();
            if (calls.isEmpty()) {
                return textOf(ai);
            }
            log.info("模型请求调用工具（{} 次调用，第 {} 轮）", calls.size(), round);
            wire.add(ai);
            for (ToolExecutionRequest call : calls) {
                wire.add(ToolExecutionResultMessage.from(call, execute(call, registered)));
            }
        }
        // 轮次用尽：收掉工具、按档位还原 json mode 再要一次正文（单次尝试）
        String text = textOf(chat(wire, parameters(options, null), options).aiMessage());
        if (text.isBlank()) {
            log.warn("工具轮用尽后正文为空，补要一次（并明确禁止再调工具）");
            List<ChatMessage> retry = new ArrayList<>(wire);
            retry.add(UserMessage.from("现在不要再调用任何工具：请直接基于上面已经查到的信息，输出最终 JSON。"));
            String retried = textOf(chat(retry, parameters(options, null), options).aiMessage());
            if (!retried.isBlank()) {
                return retried;
            }
        }
        return text;
    }

    private record RegisteredTool(String name, ToolSpecification spec, ToolExecutor executor) {
    }

    /** 从 @Tool 注解对象提取 spec 与执行器；执行器包上工具调用埋点与错误回填。 */
    private static List<RegisteredTool> register(List<Object> tools) {
        List<RegisteredTool> out = new ArrayList<>();
        for (Object tool : tools) {
            for (AiServiceTool t : ToolService.findTools(tool)) {
                ToolExecutor raw = t.toolExecutor();
                out.add(new RegisteredTool(t.name(), t.toolSpecification(),
                        (ToolExecutor) (request, memoryId) -> {
                            LlmTrace.toolCall(LlmTrace.nextId(), request.name(), request.arguments());
                            try {
                                return raw.execute(request, memoryId);
                            } catch (RuntimeException e) {
                                // 检索失败也要把结果回填，让模型基于已有上下文继续，而不是整轮报废
                                return "（工具检索失败：" + truncate(e.getMessage())
                                        + "。请基于上下文里已有的信息继续判断）";
                            }
                        }));
            }
        }
        return out;
    }

    /** 执行一次工具调用；任何异常都被 register 包过的执行器转成回填文案。 */
    private static String execute(ToolExecutionRequest call, List<RegisteredTool> registered) {
        String name = call.name();
        for (RegisteredTool tool : registered) {
            if (tool.name().equals(name)) {
                return tool.executor().execute(call, null);
            }
        }
        return "未知工具：" + name;
    }

    private ChatResponse chat(List<ChatMessage> messages, OpenAiChatRequestParameters params,
                              ChatOptions options) {
        try {
            return modelFor(options.timeout()).chat(ChatRequest.builder()
                    .messages(messages)
                    .parameters(params)
                    .build());
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private ChatModel modelFor(Duration timeout) {
        return models.computeIfAbsent(timeout, t -> OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(t)
                .maxRetries(0)
                .listeners(traceListener())
                .build());
    }

    /**
     * 逐调用参数：json mode 与 tools 互斥（deepseek 同传两者会拒绝调用或不再发 tool_calls）；
     * 思考档 off → thinking disabled + effort none，其余档位 enabled + effort。
     */
    private OpenAiChatRequestParameters parameters(ChatOptions options,
                                                   List<ToolSpecification> tools) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder()
                .modelName(model)
                .temperature(options.temperature())
                .maxOutputTokens(options.maxTokens());
        boolean withTools = tools != null && !tools.isEmpty();
        if (options.jsonMode() && !withTools) {
            builder.responseFormat(ResponseFormat.JSON);
        }
        if (options.reasoning() != null) {
            if ("off".equals(options.reasoning())) {
                builder.reasoningEffort("none")
                        .customParameters(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                builder.reasoningEffort(options.reasoning())
                        .customParameters(Map.of("thinking", Map.of("type", "enabled")));
            }
        }
        if (withTools) {
            builder.toolSpecifications(tools).toolChoice(ToolChoice.AUTO);
        }
        return builder.build();
    }

    /**
     * 请求/响应/错误的统一埋点：每次 HTTP 调用一条，消息原文交给 LlmTrace.request。
     * 同步调用在发起线程上触发，scope 标签（LlmTrace.scope）因此可用。
     */
    private static ChatModelListener traceListener() {
        ThreadLocal<long[]> context = new ThreadLocal<>();
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                ChatRequest request = ctx.chatRequest();
                long id = LlmTrace.nextId();
                context.set(new long[]{id, System.nanoTime()});
                LlmTrace.request(id, modelName(request), optionsView(request), request.messages());
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                long[] state = context.get();
                context.remove();
                LlmTrace.response(state == null ? LlmTrace.nextId() : state[0],
                        elapsed(state), textOf(ctx.chatResponse().aiMessage()),
                        ctx.chatResponse().finishReason() == null
                                ? "" : ctx.chatResponse().finishReason().name());
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {
                long[] state = context.get();
                context.remove();
                LlmTrace.error(state == null ? LlmTrace.nextId() : state[0], elapsed(state),
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
                Duration.ZERO,
                p.reasoningEffort());
    }

    private static String modelName(ChatRequest request) {
        return request.parameters() == null || request.parameters().modelName() == null
                ? "?" : request.parameters().modelName();
    }

    private static ChatException translate(RuntimeException e) {
        if (e instanceof ChatException ce) {
            return ce;
        }
        if (e instanceof HttpException h) {
            return new ChatException("LLM HTTP " + h.statusCode() + ": "
                    + truncate(h.getMessage()), h);
        }
        if (e instanceof dev.langchain4j.exception.TimeoutException
                || e.getCause() instanceof java.net.http.HttpTimeoutException) {
            return new ChatException("LLM 调用超时: " + truncate(e.getMessage()), e);
        }
        return new ChatException("LLM 调用失败: " + truncate(e.getMessage()), e);
    }

    private static String textOf(AiMessage ai) {
        return ai == null || ai.text() == null ? "" : ai.text();
    }

    private static String truncate(String text) {
        return text == null ? "null" : text.substring(0, Math.min(300, text.length()));
    }
}
