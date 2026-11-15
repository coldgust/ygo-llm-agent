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
 * 声明式模板：system 固定走 {@code @SystemMessage}；请求行/更正行的模板全文在
 * {@code JudgeAi} 注解里（2026-11-15 模板化），本类只按名透传分段变量。
 */
public final class JudgeAgent {

    private final JudgeAi proxy;

    public JudgeAgent(ChatModel model, DuelChatMemory memory, List<Object> tools, VerboseLog trace) {
        AiServices<JudgeAi> builder = AiServices.builder(JudgeAi.class)
                .chatModel(model)
                .chatMemory(memory);
        this.proxy = AgentModels.hooks(trace, builder, tools).build();
    }

    /** 分段变量约定见 {@code JudgeAi#decide}：空段 = ""，非空段以 "\n" 收尾。 */
    public JudgeAnswer decide(String headline, String chainSection,
                              String candidatesSection, String effectsSection) {
        return proxy.decide(headline, chainSection, candidatesSection, effectsSection);
    }

    public JudgeAnswer retry(String badChoice, String candidateCodes) {
        return proxy.retry(badChoice, candidateCodes);
    }
}
