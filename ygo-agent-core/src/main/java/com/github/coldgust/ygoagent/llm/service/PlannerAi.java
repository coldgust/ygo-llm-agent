package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 规划器持续 session 的 AiService：整局连续对话（自定义 {@code PlannerChatMemory} 持有历史，
 * 归 {@code PlannerAgent} 所有），锚点/事件批/重规划/keep 恢复共用同一个方法——
 * 都是"一条渲染好的 user 消息进对话"（模板在 PlannerPrompts，由调用方渲染）。
 * <p>档位参数 bake 在模型 defaultRequestParameters 里；抢答走 {@link PlannerUrgentAi}
 * （决策档、同一份 memory）。单实例 memory 无需 @MemoryId。
 */
interface PlannerAi {

    @UserMessage("{{prompt}}")
    PlanAnswer checkpoint(@V("prompt") String prompt);
}
