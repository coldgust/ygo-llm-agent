package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.ResilientChatModel;
import com.github.coldgust.ygoagent.llm.service.JudgeAgent;
import com.github.coldgust.ygoagent.llm.service.PlannerAgent;
import com.github.coldgust.ygoagent.llm.service.PlannerUrgentAgent;
import com.github.coldgust.ygoagent.llm.memory.PlannerChatMemory;
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
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.planner.PlannerPrompts;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
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
 * 键集合（前端/trace 依赖）、TurnMemory 的 ×N 折叠语义。深度重构期间这些不变式
 * 不得改变；文本与键集合视为对外契约。
 */
class PipelineCharacterizationTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    private static PipelineSettings cfg() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        return new PipelineSettings(true, d.baseUrl(), d.apiKey(), d.model(),
                0, 0, 20_000, 6, 0.80, 300,
                d.plannerCallTimeoutMs(), d.plannerCooldownMs(),
                d.cardRagPath(), d.judgeMaxTokens(), true, true);
    }

    private record Agents(PlannerAgent planner, PlannerUrgentAgent urgent, JudgeAgent judge) {
    }

    private Agents agents(FakeChatModel chat, PipelineSettings cfg) {
        var memory = new PlannerChatMemory("test");
        return new Agents(
                new PlannerAgent(new ResilientChatModel(chat.baked(cfg.checkpointOptions(), true)),
                        cfg.pathPlanning() ? PlannerPrompts.PLANNER_SYSTEM : PlannerPrompts.ADVISOR_SYSTEM,
                        memory, List.of(), "", trace),
                new PlannerUrgentAgent(new ResilientChatModel(chat.baked(
                        ChatOptions.decision(cfg.judgeMaxTokens()).withReasoning(cfg.reasoningDecision()), true)),
                        cfg.pathPlanning() ? PlannerPrompts.PLANNER_SYSTEM : PlannerPrompts.ADVISOR_SYSTEM,
                        memory, List.of(), trace),
                new JudgeAgent(new ResilientChatModel(chat.baked(
                        ChatOptions.decision(cfg.judgeMaxTokens()).withReasoning(cfg.reasoningDecision()),
                        false)), List.of(), trace));
    }

    private static DecisionPoint mainPoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001)));
    }

    private static String planJson(String verb, long passcode) {
        return "{\"thinking\": \"测试思路\", \"goal\": \"测试目标\", \"main_line\": {\"steps\": ["
                + "{\"when\": \"MAIN1\", \"verb\": \"" + verb + "\", \"passcode\": " + passcode
                + ", \"why\": \"测试\"}]}, \"confidence\": 0.8}";
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
    void execDecisionPayloadKeysAndPlanStepAreStable() {
        EventCatcher catcher = new EventCatcher();
        FakeChatModel chat = new FakeChatModel(planJson("activate", TestDuels.ENGAGE), "{\"keep\": true}");
        PlannerSession planner = new PlannerSession(agents(chat, cfg()).planner(), agents(chat, cfg()).urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), cfg(), trace);
        DecisionPipeline port = new DecisionPipeline(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                agents(chat, cfg()).judge(), cfg(), TestDuels.facts(),
                (p, d) -> new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                        DecisionExplanation.of("delegate"), Timing.ZERO),
                catcher, trace);

        var point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests(); // 锚点发布计划
        port.decide(point, DecisionDeadline.of(3000));

        assertEquals(1, catcher.execEvents.size(), "一次决策一条 EXEC_DECISION");
        Map<String, Object> payload = catcher.execEvents.getFirst();
        assertEquals(Set.of("level", "levelLabel", "requestLabel", "intent", "intentLabel",
                        "confidence", "missReasons", "explanation",
                        "planId", "planCursor", "planSteps", "planGoal", "planStep",
                        "seq", "turn", "phase", "requestKind"),
                payload.keySet(), "payload 键集合是对外契约:\n" + payload.keySet());
        assertEquals("PLAN_STEP", payload.get("level"));
        assertEquals(0, payload.get("planStep"), "命中计划步#0 → planStep=0");
    }

    @Test
    void judgePayloadOmitsPlanFieldsWhenNoPlan() {
        EventCatcher catcher = new EventCatcher();
        FakeChatModel chat = new FakeChatModel("{\"choice\": \"C1\", \"rationale\": \"r\", \"confidence\": 0.5}");
        PlannerSession planner = new PlannerSession(agents(chat, cfg()).planner(), agents(chat, cfg()).urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), cfg(), trace);
        DecisionPipeline port = new DecisionPipeline(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                agents(chat, cfg()).judge(), cfg(), TestDuels.facts(),
                (p, d) -> new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                        DecisionExplanation.of("delegate"), Timing.ZERO),
                catcher, trace);

        port.decide(mainPoint(), DecisionDeadline.of(3000));

        Map<String, Object> payload = catcher.execEvents.getFirst();
        assertEquals(Set.of("level", "levelLabel", "requestLabel", "intent", "intentLabel",
                        "confidence", "missReasons", "explanation",
                        "seq", "turn", "phase", "requestKind"),
                payload.keySet(), "无计划时 planId/planCursor/planSteps/planGoal/planStep 不出现");
        assertEquals("LLM_CALL", payload.get("level"));
    }

    @Test
    void turnMemoryFoldsConsecutiveIdenticalActionLines() {
        TurnMemory memory = new TurnMemory();
        memory.recordTurnAction(3, "判断A", "通常召唤 大黄蜂浮游单元(75982077)");
        memory.recordTurnAction(3, "判断A", "通常召唤 大黄蜂浮游单元(75982077)");
        memory.recordTurnAction(3, "判断A", "通常召唤 大黄蜂浮游单元(75982077)");
        assertEquals(List.of("通常召唤 大黄蜂浮游单元(75982077) ×3"), memory.turnActions(),
                "连续同文动作折叠 ×N");
    }

    @Test
    void turnMemoryKeepsAtMostEightLinesAndResetsOnTurnChange() {
        TurnMemory memory = new TurnMemory();
        for (int i = 0; i < 10; i++) {
            memory.recordTurnAction(3, "判断" + i, "动作" + i);
        }
        assertEquals(8, memory.turnActions().size(), "动作列表有界");
        assertEquals("动作2", memory.turnActions().getFirst(), "超限丢最旧的");
        memory.recordTurnAction(4, "新回合", "动作4-1");
        assertEquals(List.of("动作4-1"), memory.turnActions(), "换回合清空重来");
    }
}
