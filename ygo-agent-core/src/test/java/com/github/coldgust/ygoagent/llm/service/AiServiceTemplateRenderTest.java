package com.github.coldgust.ygoagent.llm.service;

import com.github.coldgust.ygoagent.llm.FakeChatModel;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiService 模板渲染钉子（2026-11-15 模板化改造的回归档案）：请求行/更正行/锚点/重规划/
 * 复盘/连招摘要的<b>固定句式全部迁入 @UserMessage 注解</b>后，渲染结果必须与旧的手工拼接
 * <b>逐字节一致</b>——这些文本进 DuelChatMemory（入史行形态）、被提示词设计文档引用，
 * 不是可以随意改写的实现细节。
 *
 * <p>分段变量约定（JudgeAi）：空段 = ""（占位符变量缺失框架会抛错），非空段以 "\n" 收尾；
 * 本测试同时钉住"全段/空段"两种形态，防回归成"空段留下空行"。
 */
class AiServiceTemplateRenderTest {

    /** 直接架在假模型上的最小 AiService（真实框架路径渲染模板，不走 ResilientChatModel）。 */
    private final FakeChatModel fake = new FakeChatModel("{}");

    private JudgeAi judge() {
        return AiServices.builder(JudgeAi.class)
                .chatModel(fake)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }

    private PlannerAi planner() {
        return AiServices.builder(PlannerAi.class)
                .chatModel(fake)
                .build();
    }

    private NarrativeAi narrator() {
        return AiServices.builder(NarrativeAi.class)
                .chatModel(fake)
                .systemMessage("sys")
                .build();
    }

    /**
     * 模板消息文本：POJO 返回类型下框架会把 JSON 格式指令追加到<b>同一条</b> user 消息文本末尾
     * （生产行为，模板化之前就如此），取时截掉这个后缀再比对。
     */
    private String renderedTemplate() {
        return fake.lastMessages().stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.UserMessage)
                .map(m -> ((dev.langchain4j.data.message.UserMessage) m).singleText())
                .reduce((a, b) -> b)
                .map(t -> {
                    int i = t.indexOf("\nYou must answer strictly");
                    return i >= 0 ? t.substring(0, i) : t;
                })
                .orElseThrow();
    }

    /**
     * 快照与请求行相对顺序的真钉子（2026-11-15 以实机 wire 日志 + LangChain4j 1.23.0
     * DefaultAiServices#assembleMessages 源码核实）：框架<b>先读 memory.messages()
     * 再 append 本次 user 消息</b>——首轮 wire 上 staged 快照位于请求行<b>之前</b>、
     * 请求行是最后一条消息（格式指令追加在同一文本内），且调用后请求行已入史（durable）。
     * 提示词措辞据此不写"随后的/下方"之类方向词，system 用中性表述"本次请求随附"。
     */
    @Test
    void judgeSnapshotWireOrderIsBeforeTheRequestLine() {
        DuelChatMemory memory = DuelChatMemory.forJudge("wire-order",
                SystemMessage.from("固定系统提示"), null);
        JudgeAi judge = AiServices.builder(JudgeAi.class)
                .chatModel(fake)
                .chatMemory(memory)
                .build();
        memory.stageOnce(UserMessage.from("[决策快照] 测试快照内容"));

        judge.decide("第 1 回合 测试抬头", "", "", "");

        List<ChatMessage> wire = fake.lastMessages();
        int snapshotIdx = indexOfText(wire, "[决策快照]");
        int requestIdx = indexOfText(wire, "===决策请求===");
        assertTrue(snapshotIdx >= 0, "快照应出现在 wire 消息里");
        assertTrue(requestIdx >= 0, "请求行应出现在 wire 消息里");
        assertTrue(snapshotIdx < requestIdx,
                "快照应在请求行之前（框架先读 messages() 再 append 本次 user 消息）: " + wire);
        assertEquals(wire.size() - 1, requestIdx, "请求行应是最后一条消息");
        assertTrue(memory.messages().stream()
                        .filter(m -> m instanceof UserMessage)
                        .map(m -> ((UserMessage) m).singleText())
                        .anyMatch(t -> t.contains("===决策请求===")),
                "调用后请求行应已入史（durable）");
    }

    private static int indexOfText(List<ChatMessage> messages, String token) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage u && u.singleText().contains(token)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void judgeDecideRendersAllSectionsExactlyAsTheOldConcatenation() {
        judge().decide("第 3 回合 主阶段（我方）", "连锁1 对手发动 增殖的G\n",
                "候选 2 项：C1 发动、C2 盖放\n", "候选效果：①抽，1：从卡组抽 1 张\n");
        assertEquals("""
                ===决策请求=== 第 3 回合 主阶段（我方）
                连锁1 对手发动 增殖的G
                候选 2 项：C1 发动、C2 盖放
                候选效果：①抽，1：从卡组抽 1 张
                请读 [决策快照] 的完整卡面与对象信息，输出单个选择 JSON。""",
                renderedTemplate());
    }

    @Test
    void judgeDecideOmitsEmptySectionsWithoutLeftoverBlankLines() {
        judge().decide("第 2 回合 战斗阶段（我方）", "", "", "");
        assertEquals("""
                ===决策请求=== 第 2 回合 战斗阶段（我方）
                请读 [决策快照] 的完整卡面与对象信息，输出单个选择 JSON。""",
                renderedTemplate());
    }

    @Test
    void judgeRetryRendersTheCorrectionLineExactlyAsTheOldConcatenation() {
        judge().retry("C9", "C0、C1、C2");
        assertEquals("""
                ===更正=== 你上一答 choice=C9 不在候选集内。本请求可选编号：C0、C1、C2。请重读 [决策快照]，只输出 JSON，choice 必须是其中之一。""",
                renderedTemplate());
    }

    @Test
    void plannerAnchorMyTurnRendersExactlyAsTheOldFormattedTemplate() {
        planner().anchorMyTurn(2);
        assertEquals("""
                ===回合锚点（第 2 回合，我方回合）===
                基于你记忆中的决斗记录与 [局面] 快照，按推演顺序给出本回合的完整建议 JSON
                （先读局三问，再定目标、盘资源上限、列候选线、给收手点）。
                options 里被选中那条的 line 要写成动作序列（至少 3 步），不要只写一个动作。
                不要写 steps/verb/passcode/zone 结构化字段——具体动作由决策器在每个决策点自行选择。
                """, renderedTemplate());
    }

    @Test
    void plannerAnchorOppTurnRendersExactlyAsTheOldFormattedTemplate() {
        planner().anchorOppTurn(5);
        assertEquals("""
                ===回合锚点（第 5 回合，对方回合）===
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
                """, renderedTemplate());
    }

    @Test
    void plannerReplanRendersExactlyAsTheOldFormattedTemplate() {
        planner().replan("对手展开线已断，原压制目标落空");
        assertEquals("""
                ===重规划（决策器要求）===
                决策器在按本回合建议执行判断时，认定这份规划已不可能实现、不合理或已偏离局面，
                原因如下：对手展开线已断，原压制目标落空
                旧建议已作废。请基于你记忆中的记录（含〔历次建议〕行）与 [局面] 快照重新规划，
                输出完整的新建议 JSON。
                重估：原目标是否已落空、原主打资源是否已被消耗、对手场面与阻抗的最新事实；
                intel 按最新事实重写，tactics 里说明为什么推翻上一份建议。
                """, renderedTemplate());
    }

    @Test
    void narratorDuelSummaryRendersExactlyAsTheOldFormattedPrompt() {
        narrator().duelSummary("双方 LP 8000", "T1 我方盖放一张卡，结束回合。");
        assertEquals("""
                【对局材料 · 固定事实】
                双方 LP 8000

                【对局材料 · 逐回合记录】
                T1 我方盖放一张卡，结束回合。

                请按系统提示里的 8 个小节写复盘。
                """, renderedTemplate());
    }

    @Test
    void narratorComboSummaryRendersExactlyAsTheOldConcatenation() {
        narrator().comboSummary("闪刀姬", "1. 启动 起动集束\n2. 结束回合\n");
        assertEquals("""
                卡组主题：闪刀姬
                该玩家回合的逐步操作记录（按时间顺序）：
                1. 启动 起动集束
                2. 结束回合

                请按系统提示的五行格式输出摘要（关键卡牌行必须给，实在没有就写“无”）。""",
                renderedTemplate());
    }

    @Test
    void narratorComboSummaryKeepsTheOldByteShapeForAnEmptyStepList() {
        narrator().comboSummary("闪刀姬", "");
        assertEquals("""
                卡组主题：闪刀姬
                该玩家回合的逐步操作记录（按时间顺序）：

                请按系统提示的五行格式输出摘要（关键卡牌行必须给，实在没有就写“无”）。""",
                renderedTemplate());
    }
}
