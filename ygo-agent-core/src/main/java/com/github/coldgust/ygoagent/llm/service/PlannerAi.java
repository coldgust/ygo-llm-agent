package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 规划器（战术幕僚）持续 session 的 AiService：整局连续对话（自定义 {@code DuelChatMemory}
 * 持有历史，归 {@code DuelAgent} 所有），锚点/修订/keep 恢复共用同一个方法——
 * 都是"一条薄请求行进对话"，局面快照由调用方 stage 进快照槽（不入 memory）。
 * <p>system 在 memory 的 [0]（构造期种入，AiServices 不再单独注入）；
 * 档位参数 bake 在模型 defaultRequestParameters 里。单实例 memory 无需 @MemoryId。
 */
interface PlannerAi {

    @UserMessage("{{prompt}}")
    AdvisorAnswer checkpoint(@V("prompt") String prompt);
}
