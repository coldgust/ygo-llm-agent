package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.judge.JudgeAnswer;
import com.github.coldgust.ygoagent.pipeline.judge.JudgePrompts;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 决策器判断（④）的 AiService：无状态单轮调用（无 memory，每次决策独立）。
 * 声明式模板：system 固定走 {@code @SystemMessage}，动态三段（静态资料/已结算回合/决策包）
 * 经 {@code @V} 注入同一条 user 消息——静态段+记录在本回合内逐字节一致，
 * deepseek 服务端前缀缓存覆盖到记录段为止。
 * <p>档位参数 bake 在模型 defaultRequestParameters 里（plain 带 json、tools 变体不带），
 * 方法不带 wire 参数。
 */
public interface JudgeAi {

    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("{{staticMaterial}}\n\n{{closedTurns}}\n\n{{decisionPack}}")
    JudgeAnswer decide(@V("staticMaterial") String staticMaterial,
                       @V("closedTurns") String closedTurns,
                       @V("decisionPack") String decisionPack);

    /** 越界重答：与 decide 相同的上下文 + 更正段（不含上一轮 assistant 回复，与旧行为一致）。 */
    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("{{decisionPack}}\n\n你的 choice={{badChoice}} 不在候选集内。"
            + "本决策点可选编号：{{candidates}}（本请求没有 C0）。请只输出 JSON，choice 必须是其中之一。")
    JudgeAnswer retry(@V("decisionPack") String decisionPack,
                      @V("badChoice") String badChoice,
                      @V("candidates") String candidates);
}
