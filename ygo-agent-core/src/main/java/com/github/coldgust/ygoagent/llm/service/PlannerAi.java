package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 规划器持续 session 的 AiService：{@code @MemoryId} 绑定每局对话（自定义
 * {@code PlannerChatMemory} 持有整局连续历史），锚点/事件批/重规划/keep 恢复共用同一份历史
 * 与同一个方法——都是"一条 user 消息进对话"（模板在 PlannerPrompts，由调用方渲染）。
 * <p>整局静态资料由调用方在首个锚点前种入 memory，成为钉住前缀
 * （[system, 静态资料] 整局逐字节稳定，跨回合前缀缓存有效）；system 提示按模式二选一，
 * 由组装方经 builder {@code systemMessage} 每局选定。抢答走 {@link PlannerUrgentAi}
 * （决策档、同一份 memory）。档位参数 bake 在模型 defaultRequestParameters 里。
 */
public interface PlannerAi {

    @UserMessage("{{prompt}}")
    PlanAnswer checkpoint(@MemoryId String duelId,
                          @V("prompt") String prompt);
}
