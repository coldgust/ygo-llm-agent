package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * 决策器判断（④）agent：带记忆的持续对话（docs/planner-judge-memory-redesign.md §7）。
 * memory 归 {@code DuelAgent} 构造并注入（pinned [system, 静态资料] 构造期种入），
 * 记录流由 {@code DuelRecordFeed} 写入；每手快照由调用方（{@code LlmJudge}）stage/drop。
 * 声明式模板：system 固定走 {@code @SystemMessage}，请求行/更正行经 {@code @V} 注入。
 */
public final class JudgeAgent {

    private final JudgeAi proxy;

    public JudgeAgent(ChatModel model, DuelChatMemory memory, List<Object> tools, VerboseLog trace) {
        AiServices<JudgeAi> builder = AiServices.builder(JudgeAi.class)
                .chatModel(model)
                .chatMemory(memory);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
    }

    public JudgeAnswer decide(String requestLine) {
        return proxy.decide(requestLine);
    }

    public JudgeAnswer retry(String correctionLine) {
        return proxy.retry(correctionLine);
    }
}
