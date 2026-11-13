package com.github.coldgust.ygoagent.llm.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;

/**
 * 叙事任务（赛后复盘、连招摘要）的 agent：无状态单轮、String 返回（Markdown 无 schema，
 * 框架不注入格式指令）。与决策侧 agent（{@code PlannerAgent}/{@code JudgeAgent}）同构：
 * 档位参数 bake 在模型上、system 提示与可选的 ContentRetriever（LightRAG 规则检索增强）
 * 构造期给定、调用点只传 prompt——提示词常量留在 server 域。
 */
public final class NarratorAgent {

    private final NarrativeAi proxy;

    public NarratorAgent(ChatModel model, String systemPrompt, ContentRetriever retriever) {
        AiServices<NarrativeAi> builder = AiServices.builder(NarrativeAi.class)
                .chatModel(model)
                .systemMessage(systemPrompt);
        if (retriever != null) {
            builder.contentRetriever(retriever);
        }
        this.proxy = builder.build();
    }

    public String write(String prompt) {
        return proxy.write(prompt);
    }
}
