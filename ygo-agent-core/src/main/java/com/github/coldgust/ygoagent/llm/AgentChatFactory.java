package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 模型实例工厂。超时是客户端级配置无法逐调用覆盖 → 按 500ms 档缓存模型实例；
 * 档位参数 bake 进模型（{@code defaultRequestParameters}），json/tools 配对在 bake 时内化
 * （withTools 不带 json_object，实测见 pitfalls §7）——桶键 =（超时档 × 档位值 × withTools）。
 * 每个实例包重试降级装饰器；埋点走 {@link ChatModelListener}（每次 HTTP 调用一条）。
 * <p>测试注入自定义模型源用包级构造；生产入口是 {@link #openAiCompatible(LlmEndpoint, VerboseLog)}。
 */
public final class AgentChatFactory {

    /** 超时档粒度：决策 deadline 是连续值，不量化会造出大量模型实例。 */
    private static final long TIMEOUT_BUCKET_MS = 500;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final VerboseLog trace;
    private final ModelSource source;
    private final ConcurrentMap<Integer, ChatModel> models = new ConcurrentHashMap<>();

    /** 模型源：按（超时 × 档位 × withTools）构建真实模型；档位参数在这里 bake 进模型。 */
    public interface ModelSource {
        ChatModel create(Duration timeout, ChatOptions tier, boolean withTools);
    }

    AgentChatFactory(String baseUrl, String apiKey, String model, VerboseLog trace, ModelSource source) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.trace = trace;
        this.source = source;
    }

    public static AgentChatFactory openAiCompatible(LlmEndpoint endpoint, VerboseLog trace) {
        VerboseLog log0 = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
        return new AgentChatFactory(endpoint.baseUrl(), endpoint.apiKey(), endpoint.model(), log0,
                (timeout, tier, withTools) -> OpenAiChatModel.builder()
                        .baseUrl(endpoint.baseUrl())
                        .apiKey(endpoint.apiKey())
                        .modelName(endpoint.model())
                        .timeout(timeout)
                        .maxRetries(0)
                        .defaultRequestParameters(TierParameters.of(tier, endpoint.model(), withTools))
                        .listeners(traceListener(log0))
                        .build());
    }

    public ChatModel modelFor(Duration timeout, ChatOptions tier, boolean withTools) {
        long bucket = Math.max(TIMEOUT_BUCKET_MS,
                ((Math.max(1, timeout.toMillis()) + TIMEOUT_BUCKET_MS - 1) / TIMEOUT_BUCKET_MS)
                        * TIMEOUT_BUCKET_MS);
        int key = 31 * (int) bucket + (withTools ? 1 : 0) + 31 * tier.hashCode();
        return models.computeIfAbsent(key, ignored -> new ResilientChatModel(
                source.create(Duration.ofMillis(bucket), tier, withTools)));
    }

    /** 供测试使用：自定义模型源的工厂（档位 bake 由模型源自行处理）。 */
    public static AgentChatFactory withSource(ModelSource source) {
        return new AgentChatFactory("http://localhost", "", "deepseek-chat",
                new VerboseLog(VerboseLogSettings.off()), source);
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
                Duration.ZERO,
                p.reasoningEffort());
    }

    private static String modelName(ChatRequest request) {
        return request.parameters() == null || request.parameters().modelName() == null
                ? "?" : request.parameters().modelName();
    }

    private static String textOf(dev.langchain4j.data.message.AiMessage ai) {
        return ai == null || ai.text() == null ? "" : ai.text();
    }
}
