package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 规划器（战术幕僚）持续 session 的 AiService：整局连续对话（自定义 {@code DuelChatMemory}
 * 持有历史，归 {@code DuelAgent} 所有）。局面快照由调用方 stage 进快照槽（不入 memory）。
 * <p>system 在 memory 的 [0]（构造期种入，AiServices 不再单独注入）；
 * 档位参数 bake 在模型 defaultRequestParameters 里。单实例 memory 无需 @MemoryId。
 *
 * <p>锚点/重规划请求行的<b>固定句式全部在下面的模板注解里</b>（2026-11-15 用户指令：
 * 提示词用 LangChain4j 模板承载，不在 Java 里拼接）——原来的三个请求模板常量
 * （ADVISOR_ANCHOR_TEMPLATE 等 + String.formatted 位置参数）已迁入：
 * 我方/对方回合锚点 prose 差异大，拆成两个方法而非一个方法加条件拼接。
 * 占位符变量全量必填（缺失框架会抛错），运行期清洗（原因截断/占位说明）留在调用方。
 */
interface PlannerAi {

    /** 我方回合锚点请求行（局面快照由调用方 stage 进快照槽，随后于本请求行，不入此模板）。 */
    @UserMessage("""
            ===回合锚点（第 {{turn}} 回合，我方回合）===
            基于你记忆中的决斗记录与 [局面] 快照，按推演顺序给出本回合的完整建议 JSON
            （先读局三问，再定目标、盘资源上限、列候选线、给收手点）。
            options 里被选中那条的 line 要写成动作序列（至少 3 步），不要只写一个动作。
            不要写 steps/verb/passcode/zone 结构化字段——具体动作由决策器在每个决策点自行选择。
            """)
    AdvisorAnswer anchorMyTurn(@V("turn") int turn);

    /** 对方回合锚点请求行——先评估机会窗口，再组织响应；不预设"防守"。 */
    @UserMessage("""
            ===回合锚点（第 {{turn}} 回合，对方回合）===
            对方回合不等于纯防守。基于记忆中的记录与快照给出建议 JSON：
            - **先核对手里哪些牌这一回合真的能用**——只有三类：已盖在魔陷区的速攻魔法/陷阱；
              手卡里写明"自己·对方回合，丢弃/送去墓地才能发动"的怪兽（手坑）；场上已表侧的效果。
              手牌里没盖的〔速攻魔法〕〔陷阱〕**本回合发不出来**，不要写进建议。
            - **再问机会窗口（答完才谈防守）**：① 反击窗口——响应的同时能不能推进我方目标
              （响应链里的斩杀线、借对手的动作给我方资源、响应中建立自己场面）；
              ② 借力窗口——对手的动作会触发我方的诱发效果吗（被破坏送墓、被其特召）；
              ③ 都没有 → 才是纯防守，按响应计划走。
            - tactics：哪几个时点值得打断（检索 / 特召 / 赚卡 / 清场）、用什么牌、哪些宁可留着、
              打到什么程度收手；若存在反击/展开线，写清"什么触发条件满足时从响应转入该线"。
            - intel：打断时点表（对手做什么时交什么、保留什么）；有反击线时补一行触发条件。
            - options：有反击/展开线时列出它与保守响应两条；纯防守时可省略或只写响应组合。
            - 不要写 steps/verb/passcode/zone 结构化字段。
            """)
    AdvisorAnswer anchorOppTurn(@V("turn") int turn);

    /**
     * 重规划请求行（决策器判决 needReplan=true 触发，每回合最多 5 次）。
     * reason = 决策器给出的重规划原因（原样转达；空白时调用方给占位说明）。
     * 旧建议在请求发起时已作废（AdvisorKeeper.retire），发布新建议前决策器不带旧规划。
     */
    @UserMessage("""
            ===重规划（决策器要求）===
            决策器在按本回合建议执行判断时，认定这份规划已不可能实现、不合理、已偏离局面，
            或带来了规划不知道的极具价值新情报，原因如下：{{reason}}
            旧建议已作废。请基于你记忆中的记录（含〔历次建议〕行）与 [局面] 快照重新规划，
            输出完整的新建议 JSON。
            重估：原目标是否已落空、原主打资源是否已被消耗、对手场面与阻抗的最新事实；
            若是新情报触发，把这条情报及其对打法的影响直接写进新建议；
            intel 按最新事实重写，tactics 里说明为什么推翻上一份建议。
            """)
    AdvisorAnswer replan(@V("reason") String reason);
}
