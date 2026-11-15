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
 *
 * <p><b>请求行/更正行的固定句式全部在下面的模板注解里</b>（2026-11-15 用户指令：
 * 提示词用 LangChain4j 模板承载，不在 Java 里拼接，接口即提示词全文）。
 * LangChain4j 模板没有条件指令，段的有无由调用方决定——分段变量约定：
 * 空段传 {@code ""}（占位符变量缺失框架会抛错），非空段以 {@code "\n"} 收尾，
 * 渲染结果与旧的手工拼接逐字节一致（入史行形态不变）。
 * "候选 N 项："这类绑定运行时计数的引导句留在 {@code LlmJudge} 的段渲染器里，紧邻取数处。
 */
interface JudgeAi {

    /**
     * 请求行：chain/candidates/effects 三段为空时整行缺席（由空段变量实现）。
     */
    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("""
            ===决策请求=== {{headline}}
            {{chainSection}}{{candidatesSection}}{{effectsSection}}请读 [决策快照] 的完整卡面与对象信息，输出单个选择 JSON。""")
    JudgeAnswer decide(@V("headline") String headline,
                       @V("chainSection") String chainSection,
                       @V("candidatesSection") String candidatesSection,
                       @V("effectsSection") String effectsSection);

    /**
     * 更正行：全量无条件变量，整行文案都在模板里。
     */
    @SystemMessage(JudgePrompts.SYSTEM)
    @UserMessage("""
            ===更正=== 你上一答 choice={{badChoice}} 不在候选集内。本请求可选编号：{{codes}}。请重读 [决策快照]，只输出 JSON，choice 必须是其中之一。""")
    JudgeAnswer retry(@V("badChoice") String badChoice, @V("codes") String codes);
}
