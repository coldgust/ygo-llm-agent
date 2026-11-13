package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.context.RuleDigest;
import com.github.coldgust.ygoagent.llm.AgentChatFactory;
import com.github.coldgust.ygoagent.llm.FakeChatModel;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.llm.service.AgentChatServices;
import com.github.coldgust.ygoagent.llm.memory.PlannerChatMemory;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征测试：规划器对话修剪必须保留 [0]=system、[1]=锚点消息（整局静态资料在其中）——
 * 前缀是跨回合服务端缓存的前提，被修剪掉缓存就断了。历史 >24 条时保留 [0..1] 与最近 12 条。
 */
class PlannerSessionTrimTest {

    private static final LlmEndpoint ENDPOINT =
            new LlmEndpoint("http://localhost:9", "k", "deepseek-chat");

    private static PipelineSettings cfg() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        // checkpoint 配额放到 30，才能在测试里把历史推过 24 条触发修剪
        return new PipelineSettings(true, d.baseUrl(), d.apiKey(), d.model(),
                0, 0, 20_000, 30, 0.80, 300,
                d.plannerCallTimeoutMs(), d.plannerCooldownMs(),
                d.cardRagPath(), d.judgeMaxTokens(), true, true);
    }

    private static DecisionPoint pointOf(int turn) {
        var state = TestDuels.state(turn, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state, List.of(), List.of());
    }

    private static String planJson() {
        return "{\"thinking\": \"t\", \"goal\": \"g\", \"main_line\": {\"steps\": []}, \"confidence\": 0.5}";
    }

    @Test
    void historyTrimKeepsSystemAndStaticMaterial() {
        FakeChatModel chat = new FakeChatModel(planJson(), "{\"keep\": true}");
        var trace = new VerboseLog(VerboseLogSettings.off());
        AgentChatServices services = new AgentChatServices(AgentChatFactory.withSource((t, tier, w) -> chat.baked(tier, w)), trace);
        // 带规则速查 → 整局静态资料非空 → 静态资料进锚点消息
        PlannerSession planner = new PlannerSession(services,
                ContextPackBuilder.builder(TestDuels.facts(), new DuelMathService())
                        .ruleDigest(RuleDigest.TEXT)
                        .advisory(true)
                        .build(),
                TestDuels.facts(), cfg(), trace);

        var point = pointOf(2);
        planner.observePoint(point);
        planner.drainForTests(); // 锚点（回复 0 = 计划）
        for (int i = 0; i < 15; i++) {
            planner.observeAnswered(point, new ChoiceIntent.EndTurn());
            planner.flushEvents();
            planner.drainForTests(); // 事件 checkpoint ×15，历史被推过 24 条
        }

        List<ChatMessage> last = chat.requests.getLast().messages();
        assertInstanceOf(SystemMessage.class, last.getFirst(), "[0] 必须是 system");
        UserMessage anchorMessage = (UserMessage) last.get(1);
        assertTrue(anchorMessage.singleText().contains("[整局资料]"),
                "[1] 必须是锚点消息（整局静态资料在里面，跨回合前缀缓存的前提）:"
                        + anchorMessage.singleText().substring(0, 60));
        // 修剪是锯齿语义：超过 24 条才剪，剪完 = [0..1] + 最近 12 条 → 在 10..25 之间振荡
        assertTrue(last.size() <= 25, "历史必须有界: " + last.size());
        assertTrue(last.size() >= 10, "最近 12 条与钉住前缀要留存: " + last.size());
        assertTrue(chat.requests.get(1).messages().size() >= 3,
                "早期调用未被修剪（system+锚点+AI 回复）");
    }

    @Test
    void systemMessageIsDeduplicatedAcrossCalls() {
        // 框架每次调用都会把 system 注入 memory：同内容必须去重，保持 [0]=system 的钉住不变式
        PlannerChatMemory memory = new PlannerChatMemory("duel-1");
        SystemMessage system = SystemMessage.from("固定系统提示");
        memory.add(system);
        memory.add(UserMessage.from("锚点"));
        memory.add(system); // 第二次调用再次注入
        assertEquals(2, memory.messages().size(),
                "同内容 system 不得累积，否则每轮请求都多一条 system");
        assertInstanceOf(SystemMessage.class, memory.messages().getFirst());
        assertEquals("锚点", ((UserMessage) memory.messages().getLast()).singleText());
    }

    @Test
    void trimNeverSplitsAToolExchange() {
        // 工具交换（AiMessage(tool_calls) ↔ ToolExecutionResultMessage）成对出现，
        // 修剪起点必须落在 UserMessage 边界上，不能从交换中间切
        PlannerChatMemory memory = new PlannerChatMemory("duel-1");
        memory.add(SystemMessage.from("sys"));
        memory.add(UserMessage.from("锚点"));
        for (int i = 0; i < 10; i++) {
            // 每轮：user 事件 → ai(工具调用) → tool 结果 → ai 最终回复 = 4 条
            memory.add(UserMessage.from("事件" + i));
            memory.add(dev.langchain4j.data.message.AiMessage.builder()
                    .toolExecutionRequests(List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id("call-" + i).name("find_card").arguments("{}").build()))
                    .build());
            memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id("call-" + i).name("find_card").arguments("{}").build(),
                    "卡面"));
            memory.add(dev.langchain4j.data.message.AiMessage.from("{\"keep\": true}"));
        }
        int before = memory.messages().size();
        assertTrue(before >= 14, "修剪在阈值上振荡，历史有界: " + before);
        // 触发下一次修剪
        memory.add(UserMessage.from("再一个事件"));
        memory.add(dev.langchain4j.data.message.AiMessage.from("{\"keep\": true}"));
        List<ChatMessage> after = memory.messages();
        // 切口两端都要对齐 UserMessage 边界，工具密的历史会略超 2+12 的名义界
        assertTrue(after.size() <= 40, "修剪要有界: " + after.size());
        // 修剪后首条非钉住消息必须是 UserMessage（工具交换没有被劈开）
        for (int i = 0; i < after.size(); i++) {
            ChatMessage m = after.get(i);
            if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
                // 它前面的消息必须是带 toolExecutionRequests 的 AiMessage（同一交换的另一半）
                ChatMessage prev = after.get(i - 1);
                assertTrue(prev instanceof dev.langchain4j.data.message.AiMessage ai
                                && ai.hasToolExecutionRequests(),
                        "工具结果必须紧跟其调用（修剪不能劈开交换），index=" + i);
            }
        }
    }

    @Test
    void 连续对话_跨回合交换保留且静态只种一次() {
        FakeChatModel chat = new FakeChatModel(planJson(), "{\"keep\": true}");
        var trace = new VerboseLog(VerboseLogSettings.off());
        AgentChatServices services = new AgentChatServices(AgentChatFactory.withSource(
                (t, tier, w) -> chat.baked(tier, w)), trace);
        PlannerSession planner = new PlannerSession(services,
                ContextPackBuilder.builder(TestDuels.facts(), new DuelMathService())
                        .ruleDigest(RuleDigest.TEXT)
                        .advisory(true)
                        .build(),
                TestDuels.facts(), cfg(), trace);

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        planner.observePoint(pointOf(2));
        planner.drainForTests();

        List<ChatMessage> last = chat.requests.getLast().messages();
        long anchors = last.stream()
                .map(m -> VerboseLog.messageText(m))
                .filter(t -> t.contains("===回合锚点（第"))
                .count();
        assertEquals(2, anchors, "两个回合的锚点交换都应留在连续对话里（不再清空）");
        long staticCount = last.stream()
                .map(m -> VerboseLog.messageText(m))
                .filter(t -> t.contains("[整局资料]"))
                .count();
        assertEquals(1, staticCount, "整局静态资料只种入一次（钉住前缀的一部分）");
        assertInstanceOf(SystemMessage.class, last.getFirst());
    }
}