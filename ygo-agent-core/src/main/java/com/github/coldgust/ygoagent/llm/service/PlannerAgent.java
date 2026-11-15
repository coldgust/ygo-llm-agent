package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * 规划器（战术幕僚）checkpoint agent：锚点（我方/对方回合）与重规划各走
 * {@code PlannerAi} 的一个模板方法——都是"一条薄请求行进对话"，局面快照由调用方
 * stage 进快照槽（不入 memory）。请求行模板全文在 {@code PlannerAi} 注解里
 * （2026-11-15 模板化），本类只按名透传变量。
 * 整局 DuelChatMemory 归 {@code DuelAgent} 构造并注入（pinned [system, 静态资料] 构造期种入，
 * 决斗记录流由 {@code DuelRecordFeed} 追加）。档位参数 bake 在模型上。
 */
public final class PlannerAgent {

    private final PlannerAi proxy;

    public PlannerAgent(ChatModel model, DuelChatMemory memory, List<Object> tools, VerboseLog trace) {
        AiServices<PlannerAi> builder = AiServices.builder(PlannerAi.class)
                .chatModel(model)
                .chatMemory(memory);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
    }

    public AdvisorAnswer anchorMyTurn(int turn) {
        return proxy.anchorMyTurn(turn);
    }

    public AdvisorAnswer anchorOppTurn(int turn) {
        return proxy.anchorOppTurn(turn);
    }

    public AdvisorAnswer replan(String reason) {
        return proxy.replan(reason);
    }
}
