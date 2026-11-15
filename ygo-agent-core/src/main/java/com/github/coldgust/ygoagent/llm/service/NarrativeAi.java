package com.github.coldgust.ygoagent.llm.service;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 叙事任务的 AiService（包级私有，由 {@code NarratorAgent} 包装）：String 返回
 * （Markdown 无 schema，框架不注入格式指令）。
 *
 * <p>复盘/连招两类请求行的<b>固定句式全部在下面的模板注解里</b>（2026-11-15 用户指令：
 * 提示词用 LangChain4j 模板承载，不在 Java 里拼接）——原来的
 * {@code DuelSummaryPrompts.user}/{@code ComboSummaryPrompts.user} 拼接已迁入，
 * server 侧只负责数据段（facts/timeline/steps 编号行）的渲染与 system 注入。
 * 占位符变量全量必填（缺失框架会抛错）。
 */
interface NarrativeAi {

    /** 赛后复盘请求行：facts/timeline 为纯数据段（无固定句），模板只承载结构标题与指令句。 */
    @UserMessage("""
            【对局材料 · 固定事实】
            {{facts}}

            【对局材料 · 逐回合记录】
            {{timeline}}

            请按系统提示里的 8 个小节写复盘。
            """)
    String duelSummary(@V("facts") String facts, @V("timeline") String timeline);

    /**
     * 连招摘要请求行：steps 为已编号的操作记录段（每行以 "\n" 收尾——空列表时该段为空，
     * 渲染结果与旧拼接逐字节一致）；archetype 已由调用方归一化（空 → "未归类"）。
     */
    @UserMessage("""
            卡组主题：{{archetype}}
            该玩家回合的逐步操作记录（按时间顺序）：
            {{steps}}
            请按系统提示的五行格式输出摘要（关键卡牌行必须给，实在没有就写“无”）。""")
    String comboSummary(@V("archetype") String archetype, @V("steps") String steps);
}
