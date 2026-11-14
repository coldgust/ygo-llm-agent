package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelRecordFeed;
import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.ResilientChatModel;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.llm.service.JudgeAgent;
import com.github.coldgust.ygoagent.llm.service.PlannerAgent;
import com.github.coldgust.ygoagent.llm.FakeChatModel;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.judge.JudgePrompts;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.planner.PlannerPrompts;
import com.github.coldgust.ygoagent.planner.PlannerRuntime;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 特征测试（重构守护网）：钉住当前行为的<b>外部可观察形态</b>——EXEC_DECISION 的 payload
 * 键集合（前端/trace 依赖）。深度重构期间这些不变式不得改变；文本与键集合视为对外契约。
 * （TurnMemory 的 ×N 折叠语义随 [本回合已做] 移除而删除：折叠现在由记录流渲染承担，
 * 见 {@code DuelRecordSectionsTest}。）
 */
class PipelineCharacterizationTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    private static PipelineSettings cfg() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        return PipelineSettings.builder(d)
                .waitPerDecisionMs(0).firstAdviceWaitMs(0)
                .waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
    }

    private record Agents(PlannerAgent planner, JudgeAgent judge,
                          DuelChatMemory plannerMemory, DuelChatMemory judgeMemory) {
    }

    private Agents agents(FakeChatModel chat, PipelineSettings cfg) {
        var plannerMemory = DuelChatMemory.forPlanner("test-planner",
                SystemMessage.from(PlannerPrompts.ADVISOR_SYSTEM), null);
        var judgeMemory = DuelChatMemory.forJudge("test-judge",
                SystemMessage.from(JudgePrompts.SYSTEM), null);
        return new Agents(
                new PlannerAgent(new ResilientChatModel(chat.baked(cfg.checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new JudgeAgent(new ResilientChatModel(chat.baked(
                        ChatOptions.decision(cfg.judgeMaxTokens()).withReasoning(cfg.reasoningDecision()),
                        false)), judgeMemory, List.of(), trace),
                plannerMemory, judgeMemory);
    }

    private static DuelRecordFeed feedFor(DuelChatMemory... memories) {
        DuelRecordFeed feed = new DuelRecordFeed(null, TestDuels.facts());
        for (DuelChatMemory memory : memories) {
            feed.attach(memory);
        }
        return feed;
    }

    private static DecisionPoint mainPoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001)));
    }

    private static String advisorJson(String goal) {
        return "{\"strategy\": \"测试策略\", \"goal\": \"" + goal
                + "\", \"tactics\": \"测试战术\", \"intel\": \"测试情报\", \"confidence\": 0.8}";
    }

    /** 捕获全部 EXEC_DECISION payload（键集合与数值都要钉住）。 */
    private static final class EventCatcher implements DecisionTraceWriter {
        final List<Map<String, Object>> execEvents = new ArrayList<>();

        @Override
        public void append(DecisionPoint point, DuelDecision decision) {
        }

        @Override
        public void event(String kind, Map<String, Object> payload) {
            if ("EXEC_DECISION".equals(kind)) {
                execEvents.add(new LinkedHashMap<>(payload));
            }
        }
    }

    @Test
    void execDecisionPayloadKeysAndAdviceFieldsAreStable() {
        EventCatcher catcher = new EventCatcher();
        FakeChatModel chat = new FakeChatModel(advisorJson("先展开再压制"),
                "{\"choice\": \"C1\", \"rationale\": \"r\", \"confidence\": 0.5}");
        Agents agents = agents(chat, cfg());
        PlannerRuntime planner = PlannerRuntime.builder(agents.planner(),
                new ContextPackBuilder(TestDuels.facts()),
                cfg(), agents.plannerMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .trace(trace)
                .build();
        DecisionPipeline port = DecisionPipeline.builder(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                agents.judge(), cfg(), TestDuels.facts())
                .delegate((p, d) -> new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                        DecisionExplanation.of("delegate"), Timing.ZERO))
                .traceWriter(catcher)
                .trace(trace)
                .judgeMemory(agents.judgeMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .build();

        var point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests(); // 锚点发布建议
        port.decide(point, DecisionDeadline.of(3000));

        assertEquals(1, catcher.execEvents.size(), "一次决策一条 EXEC_DECISION");
        Map<String, Object> payload = catcher.execEvents.getFirst();
        assertEquals(Set.of("level", "levelLabel", "requestLabel",
                        // 2026-11-15：前端描述与决策器对齐——抬头/连锁内容/阶段中文名随事件发出
                        "requestHeadline", "phaseLabel",
                        "intent", "intentLabel",
                        "confidence", "missReasons", "explanation",
                        "adviceId", "adviceTurn", "adviceStale", "adviceGoal",
                        "seq", "turn", "phase", "requestKind"),
                payload.keySet(), "payload 键集合是对外契约:\n" + payload.keySet());
        assertEquals("LLM_CALL", payload.get("level"));
        assertEquals(Boolean.FALSE, payload.get("adviceStale"), "本回合刚发布的建议不标 stale");
    }

    @Test
    void judgePayloadOmitsAdviceFieldsWhenNoAdvice() {
        EventCatcher catcher = new EventCatcher();
        FakeChatModel chat = new FakeChatModel("{\"choice\": \"C1\", \"rationale\": \"r\", \"confidence\": 0.5}");
        Agents agents = agents(chat, cfg());
        PlannerRuntime planner = PlannerRuntime.builder(agents.planner(),
                new ContextPackBuilder(TestDuels.facts()),
                cfg(), agents.plannerMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .trace(trace)
                .build();
        DecisionPipeline port = DecisionPipeline.builder(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                agents.judge(), cfg(), TestDuels.facts())
                .delegate((p, d) -> new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                        DecisionExplanation.of("delegate"), Timing.ZERO))
                .traceWriter(catcher)
                .trace(trace)
                .judgeMemory(agents.judgeMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .build();

        port.decide(mainPoint(), DecisionDeadline.of(3000));

        Map<String, Object> payload = catcher.execEvents.getFirst();
        assertEquals(Set.of("level", "levelLabel", "requestLabel",
                        "requestHeadline", "phaseLabel",
                        "intent", "intentLabel",
                        "confidence", "missReasons", "explanation",
                        "seq", "turn", "phase", "requestKind"),
                payload.keySet(), "无建议时 adviceId/adviceTurn/adviceStale/adviceGoal 不出现");
        assertEquals("LLM_CALL", payload.get("level"));
    }
}
