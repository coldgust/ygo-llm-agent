package com.github.coldgust.ygoagent.planner;

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
import com.github.coldgust.ygoagent.planner.PlannerPrompts;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;

/**
 * 回归：规划器在新回合回 {@code {"keep": true}} 时，计划必须"续期"到新回合；
 * 锚点/事件批/replan 全部走同一推理档（wire 参数在请求里断言）。
 */
class PlannerSessionCarryOverTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    /** 记录每次请求的 wire 参数与消息，按序回脚本文本（超出后一直 keep）。 */
    private static final class Recorder implements ChatModel {

        final List<OpenAiChatRequestParameters> params =
                Collections.synchronizedList(new ArrayList<>());
        final List<List<ChatMessage>> prompts =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> replies = new ArrayList<>();

        void script(String reply) {
            replies.add(reply);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            params.add((OpenAiChatRequestParameters) request.parameters());
            prompts.add(request.messages());
            int i = calls.getAndIncrement();
            String reply = i < replies.size() ? replies.get(i) : "{\"keep\": true}";
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 规划/抢答两个 agent 架在 Recorder 上（档位参数经 ResilientChatModel 合并后可断言）。 */
    private record Stack(PlannerAgent planner, PlannerUrgentAgent urgent) {
    }

    private static Stack stack(VerboseLog trace, ChatModel model) {
        PipelineSettings cfg = settings();
        var memory = new PlannerChatMemory("test");
        return new Stack(
                new PlannerAgent(
                        new ResilientChatModel(FakeChatModel.baked(model, cfg.checkpointOptions(), true)),
                        cfg.pathPlanning() ? PlannerPrompts.PLANNER_SYSTEM : PlannerPrompts.ADVISOR_SYSTEM,
                        memory, List.of(), "", trace),
                new PlannerUrgentAgent(
                        new ResilientChatModel(FakeChatModel.baked(model, ChatOptions.decision(cfg.judgeMaxTokens()).withReasoning(cfg.reasoningDecision()), true)),
                        cfg.pathPlanning() ? PlannerPrompts.PLANNER_SYSTEM : PlannerPrompts.ADVISOR_SYSTEM,
                        memory, List.of(), trace));
    }

    private static PipelineSettings settings() {
        PipelineSettings s = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        // 测试不需要真的等：把等待压到 0
        return new PipelineSettings(true, s.baseUrl(), s.apiKey(), s.model(), 0, 0,
                s.waitPerTurnMs(), s.maxCheckpointsPerTurn(), s.similarityThreshold(),
                s.judgeTimeoutMs(), s.plannerCallTimeoutMs(), s.plannerCooldownMs(),
                s.cardRagPath(), s.judgeMaxTokens(), true, true);
    }

    private static String planJson() {
        return "{\"thinking\": \"先交闪检索\", \"goal\": \"建立场面\", \"main_line\": {\"steps\": ["
                + "{\"when\": \"MAIN1\", \"verb\": \"activate\", \"passcode\": " + TestDuels.ENGAGE
                + ", \"why\": \"赚卡\"}]}, \"confidence\": 0.8}";
    }

    private static DecisionPoint pointOf(int turn) {
        var state = TestDuels.state(turn, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state, List.of(), List.of());
    }

    @Test
    void allCheckpointsShareTheReasoningTier() {
        Recorder recorder = new Recorder();
        recorder.script(planJson()); // 第一次给真计划（避免触发 keep 恢复路径），之后 keep
        var stack = stack(trace, recorder);
        PlannerSession planner = new PlannerSession(stack.planner(), stack.urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), settings(), trace);

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        assertEquals(1, recorder.params.size(), "锚点只调一次");
        assertEquals("low", recorder.params.getFirst().reasoningEffort(),
                "锚点与增量修订同走推理档");
        assertTrue(recorder.params.getFirst().maxOutputTokens() >= 8_000,
                "推理档要给足 token 预算，否则正文会被思维链挤空");

        planner.observeAnswered(pointOf(1), new ChoiceIntent.EndTurn());
        planner.flushEvents();
        planner.drainForTests();

        assertTrue(recorder.params.size() >= 2, "事件批应触发第二次 checkpoint");
        assertEquals("low", recorder.params.get(1).reasoningEffort(), "事件批同样走推理档");
        assertTrue(recorder.params.get(1).maxOutputTokens() >= 8_000);
    }

    @Test
    void reasoningLevelDrivesTheCheckpointTier() {
        // 一键调控：未配置=推理档（low）；off=快档（旧行为：锚点快出计划）；medium/high=推理档抬强度
        PipelineSettings cfg = settings();
        assertEquals("low", cfg.checkpointOptions().reasoning(), "未配置默认推理档 low");
        assertTrue(cfg.checkpointOptions().maxTokens() >= 8_000, "推理档要给足思维链预算");

        PipelineSettings off = withReasoning(cfg, "off");
        assertEquals("off", off.checkpointOptions().reasoning(), "off=快档");
        assertTrue(off.checkpointOptions().maxTokens() < 8_000, "快档不需要思维链预算");

        PipelineSettings medium = withReasoning(cfg, "medium");
        assertEquals("medium", medium.checkpointOptions().reasoning(), "medium/high 覆盖思考强度");
        assertTrue(medium.checkpointOptions().maxTokens() >= 8_000);
    }

    private static PipelineSettings withReasoning(PipelineSettings s, String reasoningPlanner) {
        return new PipelineSettings(s.enabled(), s.baseUrl(), s.apiKey(), s.model(), reasoningPlanner,
                s.reasoningDecision(), s.waitPerDecisionMs(), s.firstJudgeWaitMs(), s.waitPerTurnMs(),
                s.maxCheckpointsPerTurn(), s.similarityThreshold(), s.judgeTimeoutMs(),
                s.plannerCallTimeoutMs(), s.plannerCooldownMs(), s.cardRagPath(), s.judgeMaxTokens(),
                s.pathPlanning(), s.plannerRushAnswer());
    }

    @Test
    void replanUsesTheReasoningTierLikeEventsCheckpoints() {
        Recorder recorder = new Recorder();
        recorder.script(planJson());
        var stack = stack(trace, recorder);
        PlannerSession planner = new PlannerSession(stack.planner(), stack.urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), settings(), trace);

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        assertTrue(planner.currentPlan().isPresent());
        int callsBefore = recorder.params.size();

        planner.abandonAndReplan("测试作废");
        planner.drainForTests();

        assertTrue(recorder.params.size() > callsBefore, "作废后应触发一次 replan checkpoint");
        int replanCall = -1;
        for (int i = 0; i < recorder.prompts.size(); i++) {
            boolean marked = recorder.prompts.get(i).stream()
                    .anyMatch(m -> VerboseLog.messageText(m).contains("===重新规划==="));
            if (marked) {
                replanCall = i;
                break;
            }
        }
        assertTrue(replanCall >= 0, "replan 请求应带『===重新规划===』模板");
        assertEquals("low", recorder.params.get(replanCall).reasoningEffort(),
                "replan 应与 events 同走推理档");
        assertTrue(recorder.params.get(replanCall).maxOutputTokens() >= 8_000,
                "推理档要给足 token 预算");
    }

    @Test
    void keepWithoutAnyPlanTriggersOneRecoveryPlan() {
        Recorder recorder = new Recorder();
        recorder.script("{\"keep\": true}");
        recorder.script(planJson());
        var stack = stack(trace, recorder);
        PlannerSession planner = new PlannerSession(stack.planner(), stack.urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), settings(), trace);

        planner.observePoint(pointOf(1));
        planner.drainForTests();

        TurnPlan plan = planner.currentPlan().orElseThrow(
                () -> new AssertionError("keep 但无计划时必须补一份真实计划"));
        assertEquals(1, plan.steps().size());
        assertEquals(1, plan.turnNumber());
    }

    @Test
    void keepOnANewTurnCarriesThePlanOver() {
        Recorder recorder = new Recorder();
        recorder.script(planJson());
        var stack = stack(trace, recorder);
        PlannerSession planner = new PlannerSession(stack.planner(), stack.urgent(),
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                TestDuels.facts(), settings(), trace);

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        TurnPlan first = planner.currentPlan().orElseThrow();
        assertEquals(1, first.turnNumber());
        assertEquals(1, first.steps().size());

        // 第 2 回合：模型回 keep → 计划应续期（回合号更新、步骤不变、置信度打折、cursor 归零）
        planner.observePoint(pointOf(2));
        planner.drainForTests();

        TurnPlan carried = planner.currentPlan().orElseThrow();
        assertEquals(2, carried.turnNumber(), "keep 之后计划必须挂到新回合，否则执行器会过滤掉");
        assertEquals(first.planId(), carried.planId(), "续期沿用同一份计划的步骤");
        assertEquals(1, carried.steps().size());
        assertEquals(0, carried.cursor(), "cursor 归零，重新从第一步开始接地");
        assertTrue(carried.confidence() < first.confidence(),
                "上一回合的判断续期后置信度应打折: " + carried.confidence());
    }
}
