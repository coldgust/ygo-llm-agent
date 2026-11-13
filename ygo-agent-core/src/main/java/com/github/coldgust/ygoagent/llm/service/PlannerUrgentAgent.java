package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * ④ 升级抢答 agent：与 {@link PlannerAgent} 共享同一份对话历史（发散交换落进 history），
 * 但档位是决策档（关思考、快超时）——模型独立构建，与规划器 checkpoint 互不串。
 */
public final class PlannerUrgentAgent {

    private final PlannerUrgentAi proxy;

    public PlannerUrgentAgent(ChatModel model, String systemPrompt, ChatMemory memory,
                              List<Object> tools, VerboseLog trace) {
        AiServices<PlannerUrgentAi> builder = AiServices.builder(PlannerUrgentAi.class)
                .chatModel(model)
                .systemMessage(systemPrompt)
                .chatMemory(memory);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
    }

    public JudgeAnswer urgent(String prompt) {
        return proxy.urgent(prompt);
    }
}
