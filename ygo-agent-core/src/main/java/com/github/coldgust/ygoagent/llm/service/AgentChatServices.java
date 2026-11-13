package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentChatFactory;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AiService 代理的组装与缓存：按（角色 × 变体 × 超时档）惰性构建。
 * <ul>
 *   <li>档位参数 bake 在模型 defaultRequestParameters 上（工厂按 档位×withTools 分桶）——
 *       json/tools 配对在这里内化：注册了工具的模型不带 json_object（实测见 pitfalls §7），
 *       工具是否注册由 tools 是否为空自然决定，调用点既看不到 wire 参数也不可能配错；</li>
 *   <li>工具回路交给框架（{@code maxToolCallingRoundTrips(6)}）；埋点与错误回填走框架钩子
 *       （before/afterToolExecution、toolExecutionErrorHandler——检索失败回填文案让模型继续，
 *       而不是整轮报废）；</li>
 *   <li>代理对同一 @MemoryId 不该并发调用——规划器全部调用都排在单线程 worker 上。</li>
 * </ul>
 */
public final class AgentChatServices {

    private static final int MAX_TOOL_ROUNDS = 6;

    private final AgentChatFactory factory;
    private final VerboseLog trace;
    private final ConcurrentMap<ServiceKey, Object> services = new ConcurrentHashMap<>();

    public AgentChatServices(AgentChatFactory factory, VerboseLog trace) {
        this.factory = factory;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
    }

    public static AgentChatServices openAiCompatible(LlmEndpoint endpoint, VerboseLog trace) {
        return new AgentChatServices(AgentChatFactory.openAiCompatible(endpoint, trace), trace);
    }

    /**
     * 决策器判断（④）：无状态单轮调用；{@code tier} 为决策档。
     * 是否注册工具由 tools 是否为空自然决定（空 = 框架不启用工具轮，模型带 json_object），
     * 不需要调用点声明变体。
     */
    public JudgeAi judge(Duration timeout, List<Object> tools, ChatOptions tier) {
        return (JudgeAi) services.computeIfAbsent(
                new ServiceKey("judge", timeout, tools, tier),
                k -> new Builder<>(AiServices.builder(JudgeAi.class)
                        .chatModel(factory.modelFor(k.timeout(), k.tier(), k.withTools())))
                        .toolsIf(k)
                        .build());
    }

    /**
     * 规划器持续 session：memory 每局一份（与抢答变体共享同一实例），system 按模式选定；
     * {@code tier} 为 checkpoint 档。
     */
    public PlannerAi planner(ChatMemory memory, String systemPrompt, Duration timeout,
                             List<Object> tools, ChatOptions tier) {
        return (PlannerAi) services.computeIfAbsent(
                new ServiceKey("planner", timeout, tools, tier),
                k -> new Builder<>(AiServices.builder(PlannerAi.class)
                        .chatModel(factory.modelFor(k.timeout(), k.tier(), true))
                        .systemMessage(systemPrompt)
                        .chatMemory(memory))
                        .toolsIf(k)
                        .build());
    }

    /** ④ 升级抢答：与 checkpoint 共享同一份 memory，档位是决策档。 */
    public PlannerUrgentAi urgent(ChatMemory memory, String systemPrompt, Duration timeout,
                                  List<Object> tools, ChatOptions tier) {
        return (PlannerUrgentAi) services.computeIfAbsent(
                new ServiceKey("planner-urgent", timeout, tools, tier),
                k -> new Builder<>(AiServices.builder(PlannerUrgentAi.class)
                        .chatModel(factory.modelFor(k.timeout(), k.tier(), true))
                        .systemMessage(systemPrompt)
                        .chatMemory(memory))
                        .toolsIf(k)
                        .build());
    }

    /** 叙事任务（赛后复盘/连招摘要）；{@code tier} 为叙事档，可带 ContentRetriever 增强。 */
    public NarrativeAi narrative(Duration timeout, String systemPrompt,
                                 ContentRetriever retriever, ChatOptions tier) {
        return (NarrativeAi) services.computeIfAbsent(
                new ServiceKey("narrative", timeout, List.of(), tier),
                k -> {
                    AiServices<NarrativeAi> builder = AiServices.builder(NarrativeAi.class)
                            .chatModel(factory.modelFor(k.timeout(), k.tier(), k.withTools()))
                            .systemMessage(systemPrompt);
                    if (retriever != null) {
                        builder.contentRetriever(retriever);
                    }
                    return builder.build();
                });
    }

    /** 叙事调用的一次性门面：宿主（server）不接触 AiService 类型。 */
    public String narrate(Duration timeout, String systemPrompt, String prompt, ChatOptions tier,
                          ContentRetriever retriever) {
        return narrative(timeout, systemPrompt, retriever, tier).write(prompt);
    }

    /**
     * 组装的公共尾段：工具埋点与错误回填（框架钩子），带工具变体注册工具并限轮次。
     */
    private final class Builder<T> {
        private final AiServices<T> builder;

        private Builder(AiServices<T> builder) {
            this.builder = builder;
        }

        private Builder<T> toolsIf(ServiceKey k) {
            if (k.withTools() && !k.tools().isEmpty()) {
                builder.tools(k.tools()).maxToolCallingRoundTrips(MAX_TOOL_ROUNDS);
            }
            return this;
        }

        private T build() {
            builder
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
            return builder.build();
        }
    }

    private static String clip(String text) {
        String s = text == null ? "null" : text;
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    private record ServiceKey(String role, Duration timeout,
                              List<Object> tools, ChatOptions tier) {
        // tools 按同一 List 实例比较（调用点持有稳定的工具集合对象）；tier 是 record，按值比较

        /** 是否注册工具由 tools 是否为空自然决定——json/tools 配对随之内化，无需调用点声明。 */
        boolean withTools() {
            return !tools.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ServiceKey that)) {
                return false;
            }
            return role.equals(that.role) && timeout.equals(that.timeout)
                    && tools == that.tools && tier.equals(that.tier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(role, timeout, tier);
        }
    }
}
