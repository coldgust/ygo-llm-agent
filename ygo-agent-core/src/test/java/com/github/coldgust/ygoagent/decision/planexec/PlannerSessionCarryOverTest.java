package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlannerSession;
import com.github.coldgust.ygoagent.decision.planexec.planner.TurnPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：规划器在新回合回 {@code {"keep": true}} 时，计划必须"续期"到新回合。
 * <p>否则执行器按 turn 过滤会把它丢掉，整回合"计划尚未就绪"（实机一局出现 15 次）。
 */
class PlannerSessionCarryOverTest {

    /** 第一次返回计划，之后一直返回 keep。 */
    private static ChatClient chat(String... replies) {
        AtomicInteger calls = new AtomicInteger();
        return (messages, options) -> {
            int i = calls.getAndIncrement();
            return i < replies.length ? replies[i] : "{\"keep\": true}";
        };
    }

    private static PlannerSettings settings() {
        PlannerSettings s = PlannerSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        // 测试不需要真的等：把等待压到 0
        return new PlannerSettings(true, s.baseUrl(), s.apiKey(), s.model(), 0, 0,
                s.waitPerTurnMs(), s.maxCheckpointsPerTurn(), s.similarityThreshold(),
                s.oneshotTimeoutMs(), s.plannerCallTimeoutMs(), s.plannerCooldownMs(),
                s.cardRagPath(), s.oneshotMaxTokens(), true, true);
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
        // 锚点/重规划/增量修订统一走推理档（deepPlan 默认开）。锚点曾单独快档（off）
        // 保首动作及时性，但快档计划质量差——统一后接受"首动作可能等不到计划"的代价。
        List<ChatClient.ChatOptions> seen =
                Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        ChatClient recorder = (messages, options) -> {
            seen.add(options);
            // 第一次给真计划（避免触发 keep 恢复路径），之后 keep
            return calls.getAndIncrement() == 0 ? planJson() : "{\"keep\": true}";
        };
        PlannerSession planner = new PlannerSession(recorder,
                new ContextPackBuilder(TestDuels.facts(), DuelMathService.instance()),
                TestDuels.facts(), settings());

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        assertEquals(1, seen.size(), "锚点只调一次");
        assertEquals("low", seen.getFirst().reasoning(), "锚点与增量修订同走推理档");
        assertTrue(seen.getFirst().maxTokens() >= 8_000, "推理档要给足 token 预算，否则正文会被思维链挤空");

        planner.observeAnswered(pointOf(1),
                new ChoiceIntent.EndTurn());
        planner.flushEvents();
        planner.drainForTests();

        assertTrue(seen.size() >= 2, "事件批应触发第二次 checkpoint");
        assertEquals("low", seen.get(1).reasoning(), "事件批同样走推理档");
        assertTrue(seen.get(1).maxTokens() >= 8_000);
    }

    @Test
    void reasoningLevelDrivesTheCheckpointTier() {
        // 一键调控：未配置=推理档（low）；off=快档（旧行为：锚点快出计划）；medium/high=推理档抬强度
        PlannerSettings cfg = settings();
        assertEquals("low", cfg.checkpointOptions(null).reasoning(), "未配置默认推理档 low");
        assertTrue(cfg.checkpointOptions(null).maxTokens() >= 8_000, "推理档要给足思维链预算");

        assertEquals("off", cfg.checkpointOptions("off").reasoning(), "off=快档");
        assertTrue(cfg.checkpointOptions("off").maxTokens() < 8_000, "快档不需要思维链预算");

        assertEquals("medium", cfg.checkpointOptions("medium").reasoning(), "medium/high 覆盖思考强度");
        assertTrue(cfg.checkpointOptions("medium").maxTokens() >= 8_000);
    }

    @Test
    void replanUsesTheReasoningTierLikeEventsCheckpoints() {
        // replan 曾与锚点同走快档（thinking=off）：实测快档产出的计划常低质量、与前面矛盾。
        // 现在 replan 与 events checkpoint 同档（low），都受 LLM_REASONING_PLANNER 覆盖。
        List<ChatClient.ChatOptions> seen = Collections.synchronizedList(new ArrayList<>());
        List<List<ChatClient.Msg>> prompts = Collections.synchronizedList(new ArrayList<>());
        ChatClient recorder = (messages, options) -> {
            prompts.add(List.copyOf(messages));
            seen.add(options);
            return planJson();
        };
        PlannerSession planner = new PlannerSession(recorder,
                new ContextPackBuilder(TestDuels.facts(), DuelMathService.instance()),
                TestDuels.facts(), settings());

        planner.observePoint(pointOf(1));
        planner.drainForTests();
        assertTrue(planner.currentPlan().isPresent());
        int callsBefore = seen.size();

        planner.abandonAndReplan("测试作废");
        planner.drainForTests();

        assertTrue(seen.size() > callsBefore, "作废后应触发一次 replan checkpoint");
        int replanCall = -1;
        for (int i = 0; i < prompts.size(); i++) {
            boolean marked = prompts.get(i).stream()
                    .anyMatch(m -> m.content().contains("===重新规划==="));
            if (marked) {
                replanCall = i;
                break;
            }
        }
        assertTrue(replanCall >= 0, "replan 请求应带『===重新规划===』模板");
        assertEquals("low", seen.get(replanCall).reasoning(), "replan 应与 events 同走推理档");
        assertTrue(seen.get(replanCall).maxTokens() >= 8_000, "推理档要给足 token 预算");
    }

    @Test
    void keepWithoutAnyPlanTriggersOneRecoveryPlan() {
        // 实机事故：锚点解析失败 → 之后每次 checkpoint 都回 keep → 没有可续期的计划，
        // 旧代码静默 return，于是整回合（25 次决策）都没有计划。
        // 现在：keep + 无计划 → 每段补一次"重新规划"，拿到计划为止。
        PlannerSession planner = new PlannerSession(
                chat("{\"keep\": true}", planJson()),
                new ContextPackBuilder(TestDuels.facts(), DuelMathService.instance()),
                TestDuels.facts(), settings());

        planner.observePoint(pointOf(1));
        planner.drainForTests();

        TurnPlan plan = planner.currentPlan().orElseThrow(
                () -> new AssertionError("keep 但无计划时必须补一份真实计划"));
        assertEquals(1, plan.steps().size());
        assertEquals(1, plan.turnNumber());
    }

    @Test
    void keepOnANewTurnCarriesThePlanOver() {
        PlannerSession planner = new PlannerSession(chat(planJson()),
                new ContextPackBuilder(TestDuels.facts(), DuelMathService.instance()),
                TestDuels.facts(), settings());

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
