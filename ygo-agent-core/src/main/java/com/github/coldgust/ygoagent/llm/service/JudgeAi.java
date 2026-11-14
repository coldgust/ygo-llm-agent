package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import com.github.coldgust.ygoagent.pipeline.judge.JudgePrompts;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 决策器判断（④）的 AiService：带记忆的持续对话（docs/planner-judge-memory-redesign.md §7）。
 * 静态资料与决斗记录已在 memory（pinned + 追加流），每手只发一条薄请求行——
 * 厚内容（局面/规划器情报/当前效果/候选）由调用方 stage 进快照槽（不入 memory，
 * 框架每轮重读 messages() 时可见，快照永远跟随最新请求行）。
 * <p>档位参数 bake 在模型 defaultRequestParameters 里（plain 带 json、tools 变体不带），
 * 方法不带 wire 参数。重答共用同一 memory：坏回复已入史（框架行为，见 AgentServicesSpikeTest
 * 钉子），更正行只需引用 badChoice 与候选编号。
 */
interface JudgeAi {

    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("{{requestLine}}")
    JudgeAnswer decide(@V("requestLine") String requestLine);

    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("{{correctionLine}}")
    JudgeAnswer retry(@V("correctionLine") String correctionLine);
}
