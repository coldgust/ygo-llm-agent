package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import com.github.coldgust.ygoagent.pipeline.judge.JudgePrompts;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * 决策器判断（④）agent：无状态单轮调用（无 memory，每次决策独立）。
 * 声明式模板：system 固定走 {@code @SystemMessage}，动态三段（静态资料/已结算回合/决策包）
 * 经 {@code @V} 注入同一条 user 消息——静态段+记录在本回合内逐字节一致，
 * deepseek 服务端前缀缓存覆盖到记录段为止。越界重答与直答同构，仅追加更正段。
 */
public final class JudgeAgent {

    private final JudgeAi proxy;

    public JudgeAgent(ChatModel model, List<Object> tools, VerboseLog trace) {
        AiServices<JudgeAi> builder = AiServices.builder(JudgeAi.class)
                .chatModel(model);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
    }

    public JudgeAnswer decide(String staticMaterial, String closedTurns, String decisionPack) {
        return proxy.decide(staticMaterial, closedTurns, decisionPack);
    }

    public JudgeAnswer retry(String decisionPack, String badChoice, String candidates) {
        return proxy.retry(decisionPack, badChoice, candidates);
    }
}
