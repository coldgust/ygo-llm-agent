package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * 规划器 checkpoint agent：锚点/事件批/重规划/keep 恢复共用同一个方法——
 * 都是"一条渲染好的 user 消息进对话"（模板在 PlannerPrompts，由调用方渲染）。
 * 持有整局连续对话记忆（与抢答共享，归 DuelAgent 构造并注入），整局静态资料在
 * 构造时种入一次（[system, 静态资料] 是整局钉住前缀）。档位参数 bake 在模型上。
 */
public final class PlannerAgent {

    private final PlannerAi proxy;

    public PlannerAgent(ChatModel model, String systemPrompt, ChatMemory memory,
                        List<Object> tools, String staticMaterial, VerboseLog trace) {
        AiServices<PlannerAi> builder = AiServices.builder(PlannerAi.class)
                .chatModel(model)
                .systemMessage(systemPrompt)
                .chatMemory(memory);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
        // 整局静态资料只种入一次：等首个调用把 system 注入 memory 后，[system, 静态资料] 即钉住前缀
        if (staticMaterial != null && !staticMaterial.isBlank()) {
            memory.add(dev.langchain4j.data.message.UserMessage.from(staticMaterial));
        }
    }

    public PlanAnswer checkpoint(String prompt) {
        return proxy.checkpoint(prompt);
    }
}
