package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * ④ 升级抢答的 AiService：与 {@link PlannerAi} 共享同一份对话历史（发散交换落进 history），
 * 但档位是决策档（关思考、快超时）——参数 bake 在独立构建的服务实例上，与规划器 checkpoint 互不串。
 */
interface PlannerUrgentAi {

    @UserMessage("{{prompt}}")
    JudgeAnswer urgent(@V("prompt") String prompt);
}
