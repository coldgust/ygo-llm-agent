package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.llm.AgentChatFactory;
import com.github.coldgust.ygoagent.llm.FakeChatModel;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.llm.service.AgentChatServices;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.model.Candidates;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPipelineTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    private static final LlmEndpoint ENDPOINT =
            new LlmEndpoint("http://localhost:9", "k", "deepseek-chat");

    /** AiServices 代理直接架在假模型上：解析/模板/记忆都走真实框架路径。 */
    private static AgentChatServices stack(VerboseLog trace, ChatModel model) {
        return new AgentChatServices(AgentChatFactory.withSource((t, tier, w) -> model), trace);
    }

    private static PipelineSettings settings() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        return new PipelineSettings(d.enabled(), d.baseUrl(), d.apiKey(), d.model(),
                d.waitPerDecisionMs(), d.firstJudgeWaitMs(), d.waitPerTurnMs(),
                d.maxCheckpointsPerTurn(), d.similarityThreshold(), d.judgeTimeoutMs(),
                d.plannerCallTimeoutMs(), d.plannerCooldownMs(), d.cardRagPath(),
                d.judgeMaxTokens(), true, true);
    }

    private static DecisionPoint mainPoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001)));
    }

    private static String planJson(String verb, long passcode) {
        return "{\"thinking\": \"测试思路\", \"goal\": \"测试目标\", \"main_line\": {\"steps\": ["
                + "{\"when\": \"MAIN1\", \"verb\": \"" + verb + "\", \"passcode\": " + passcode + ", \"why\": \"测试\"}"
                + "]}, \"confidence\": 0.8}";
    }

    private record Rig(DecisionPipeline port, PlannerSession planner, FakeChatModel chat,
                       AtomicInteger delegateCalls) {
    }

    /** 测试用：把"整回合等计划"压到很短，避免用例被 7s 等待拖慢。 */
    private static PipelineSettings fastWait() {
        PipelineSettings s = settings();
        return new PipelineSettings(true, s.baseUrl(), s.apiKey(), s.model(),
                400, 400, s.waitPerTurnMs(), s.maxCheckpointsPerTurn(),
                s.similarityThreshold(), s.judgeTimeoutMs(), s.plannerCallTimeoutMs(),
                s.plannerCooldownMs(), s.cardRagPath(), s.judgeMaxTokens(), true, true);
    }

    private Rig rig(String... chatReplies) {
        return rig(settings(), chatReplies);
    }

    private Rig rig(PipelineSettings cfg, String... chatReplies) {
        FakeChatModel chat = new FakeChatModel(chatReplies);
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), cfg, trace);
        AtomicInteger delegateCalls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            delegateCalls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var port = new DecisionPipeline(new ReflexPolicyImpl(),
                planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, cfg, TestDuels.facts(), delegate,
                DecisionTraceWriter.NOOP, trace);
        return new Rig(port, planner, chat, delegateCalls);
    }

    @Test
    void reflexWinsBeforePlan() {
        Rig rig = rig();
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        // 只有描述码、没有可读文本的确认类请求，REFLEX 仍可直接答（不烧 LLM 调用）
        var request = new ChoiceRequest.YesNoRequest(7);
        var yesNoPoint = new DecisionPoint(9, 2, state.phase(),
                PlayerSide.SELF, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.YES_NO);
        DuelDecision decision = rig.port().decide(yesNoPoint, DecisionDeadline.of(1000));
        assertEquals(PathKind.ONLY_CHOICE, decision.pathKind());
        assertEquals(0, rig.delegateCalls().get());
        rig.planner().drainForTests();
        // 规划器照常观察（回合锚点），但决策路径没有走 直答：
        // 调用数 = 锚点 1 次（rig 默认回 keep），关键是"没有第 3 次决策器判断"。
        assertTrue(rig.chat().requests.size() <= 2,
                "REFLEX 命中后不应再调 LLM 做决策器判断，实际调用 " + rig.chat().requests.size());
    }

    @Test
    void optionalChainIsNotSwallowedByReflex() {
        Rig rig = rig(fastWait(), "{\"choice\":\"C0\",\"rationale\":\"保留手牌，这一环不值得断\",\"confidence\":0.7}");
        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.effectChoice(20, TestDuels.ENGAGE, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));
        DecisionPoint point = new DecisionPoint(11, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));

        assertFalse(decision.pathKind() == PathKind.ONLY_CHOICE, "可选连锁必须交给规划器/直答");
        assertInstanceOf(ChoiceIntent.ChainDecline.class, decision.intent(),
                "模型选择 C0 时应落地为弃权");
    }

    @Test
    void exactPlanMatchExecutesStepAndAdvances() {
        // 第二条回复给 keep：主阶段决策后会触发 events checkpoint，
        // 若它再"发布一份新计划"就会把 cursor 归零，掩盖了"已推进"这件事（真模型此时通常回 keep）
        Rig rig = rig(planJson("activate", TestDuels.ENGAGE), "{\"keep\": true}");
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        assertTrue(rig.planner().currentPlan().isPresent(), "锚点 checkpoint 应产出计划");

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(1000));
        assertEquals(PathKind.PLAN_STEP, decision.pathKind());
        assertEquals(ChoiceIntent.Activate.class, decision.intent().getClass());
        assertEquals(0, rig.delegateCalls().get());
        assertEquals(1, rig.planner().currentPlan().orElseThrow().cursor());
    }

    @Test
    void ungroundablePlanFallsToOneShot() {
        Rig rig = rig(
                planJson("activate", 123456789L),                                    // 锚点：无效计划
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}"); // 直答
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        assertTrue(rig.planner().currentPlan().isPresent());

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind());
        assertEquals(ChoiceIntent.Summon.class, decision.intent().getClass());
        assertEquals(0, rig.delegateCalls().get());
    }

    @Test
    void rushAnswerOffSkipsPlannerInEscalation() {
        // 抢答开关关闭：④升级只跑直答，规划会话不应收到"===发散"的紧急决策请求
        PipelineSettings s = settings();
        PipelineSettings off = new PipelineSettings(true, s.baseUrl(), s.apiKey(), s.model(),
                s.waitPerDecisionMs(), s.firstJudgeWaitMs(), s.waitPerTurnMs(),
                s.maxCheckpointsPerTurn(), s.similarityThreshold(), s.judgeTimeoutMs(),
                s.plannerCallTimeoutMs(), s.plannerCooldownMs(), s.cardRagPath(),
                s.judgeMaxTokens(), true, false);
        Rig rig = rig(off,
                planJson("activate", 999999999L),                                    // 锚点：无效计划
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}"); // 直答
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind(), "升级仍应由直答接住");
        assertTrue(rig.chat().requests.stream()
                        .flatMap(r -> r.messages().stream())
                        .noneMatch(m -> VerboseLog.messageText(m).contains("===发散")),
                "抢答关闭后规划会话不应收到紧急决策请求");
        assertEquals(0, rig.delegateCalls().get());
    }

    private static String planJsonWithZone(String verb, long passcode, String zone) {
        return "{\"thinking\": \"测试思路\", \"goal\": \"测试目标\", \"main_line\": {\"steps\": ["
                + "{\"when\": \"MAIN1\", \"verb\": \"" + verb + "\", \"passcode\": " + passcode
                + ", \"zone\": \"" + zone + "\", \"why\": \"抢纵列\"}]}, \"confidence\": 0.8}";
    }

    /** 位置请求：三个空格，C1/C2/C3 对应 MMZ-1/2/3。 */
    private static DecisionPoint placePoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var request = new ChoiceRequest.PlaceRequest(1,
                List.of(ZoneRef.mainMonster(
                                PlayerSide.SELF, 0),
                        ZoneRef.mainMonster(
                                PlayerSide.SELF, 1),
                        ZoneRef.mainMonster(
                                PlayerSide.SELF, 2)));
        return new DecisionPoint(20, 2, state.phase(),
                PlayerSide.SELF, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.PLACE);
    }

    @Test
    void plannedZoneIsHonouredByTheFollowingPlaceRequest() {
        // 计划"召唤大黄蜂 → MMZ-2"，引擎随后单独问"放哪格"：这一步必须按计划放 C2
        Rig rig = rig(fastWait(), planJsonWithZone("summon", TestDuels.HORNET, "MMZ-2"),
                "{\"keep\": true}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();
        assertTrue(rig.planner().currentPlan().isPresent());

        DuelDecision summon = rig.port().decide(main, DecisionDeadline.of(1000));
        assertEquals(PathKind.PLAN_STEP, summon.pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        assertEquals(PathKind.PLAN_STEP, place.pathKind(), "位置应由计划兑现，不烧 LLM");
        var intent = (ChoiceIntent.Place) place.intent();
        assertEquals(List.of("C2"), intent.zones().stream().map(id -> id.value()).toList(),
                "计划写 MMZ-2，就该提交 C2");
        assertEquals(0, rig.delegateCalls().get());
    }

    @Test
    void placeRequestWithoutPlannedZoneFallsBackToFirstZoneWithoutAskingLlm() {
        // 模型没给位置（计划步也没写）：直接取第一个可放置区域，
        // 不额外发一次请求问位置，也不因此作废计划
        Rig rig = rig(fastWait(),
                planJson("summon", TestDuels.HORNET),
                "{\"keep\": true}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();

        DuelDecision summon = rig.port().decide(main, DecisionDeadline.of(1000));
        assertEquals(PathKind.PLAN_STEP, summon.pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        // ONLY_CHOICE = ① REFLEX 直接答，没有为位置发起任何 LLM 决策调用
        // （后台规划器 checkpoint 可能另有用量，所以不按 chat 调用总数断言）
        assertEquals(PathKind.ONLY_CHOICE, place.pathKind(), "没给位置就走规则兜底，不问 LLM");
        assertEquals(List.of("C1"), ((ChoiceIntent.Place) place.intent()).zones().stream()
                .map(id -> id.value()).toList(), "兜底取第一个可放置区域");
        assertEquals(1, rig.planner().currentPlan().orElseThrow().steps().size(),
                "位置请求接不上地不该把计划作废成骨架");
    }

    @Test
    void judgeZoneInTheSameAnswerIsHonouredByTheFollowingPlaceRequest() {
        // 模型在 直答 里选动作时一并给出 zone（同一次回答）→ 位置请求直接兑现，不再问
        Rig rig = rig(fastWait(),
                planJson("summon", 999999999L),   // 接不上地 → 走 ④ 决策器判断
                "{\"keep\": true}",
                "{\"choice\": \"C1\", \"zone\": \"MMZ-2\", \"rationale\": \"站中间列\", \"confidence\": 0.8}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();

        DuelDecision summon = rig.port().decide(main, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, summon.pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        // "不再问"由路径种类兜底：位置若没被兑现，这里会是 LLM_CALL/TACTICAL 而非 PLAN_STEP。
        assertEquals(PathKind.PLAN_STEP, place.pathKind(), "位置应由 直答 同一次回答给出并兑现");
        assertEquals(List.of("C2"), ((ChoiceIntent.Place) place.intent()).zones().stream()
                .map(id -> id.value()).toList(), "模型给 MMZ-2，就该提交 C2");
    }

    @Test
    void illegalOneshotZoneFallsBackToTheFirstZone() {
        // 模型给的格子不可放（不在这批候选里）→ 视为没给，取第一个
        Rig rig = rig(fastWait(),
                planJson("summon", 999999999L),
                "{\"keep\": true}",
                "{\"choice\": \"C1\", \"zone\": \"MMZ-5\", \"rationale\": \"想放最右\", \"confidence\": 0.8}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();
        assertEquals(PathKind.LLM_CALL, rig.port().decide(main, DecisionDeadline.of(3000)).pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        assertEquals(PathKind.ONLY_CHOICE, place.pathKind());
        assertEquals(List.of("C1"), ((ChoiceIntent.Place) place.intent()).zones().stream()
                .map(id -> id.value()).toList());
    }

    @Test
    void chainWindowDoesNotAbandonAnActivePlan() {
        // 连锁在计划里只是"预测"（对手检索就丢灰）：预测没命中不等于主计划破产。
        Rig rig = rig(fastWait(),
                planJson("summon", TestDuels.HORNET),
                "{\"keep\": true}",
                "{\"choice\": \"C0\", \"rationale\": \"这一环不值得断\", \"confidence\": 0.6}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();
        assertEquals(1, rig.planner().currentPlan().orElseThrow().steps().size());

        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.effectChoice(50, TestDuels.ENGAGE, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));
        DecisionPoint chain = new DecisionPoint(30, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);

        rig.port().decide(chain, DecisionDeadline.of(3000));

        assertEquals(1, rig.planner().currentPlan().orElseThrow().steps().size(),
                "连锁窗口不该把主线计划作废成骨架");
    }

    @Test
    void optionalChainChoiceIsHonoured() {
        // 可选的连锁：模型选 C1 就执行 C1，执行器不做"更优判断"的替换
        Rig rig = rig(fastWait(),
                "{\"choice\": \"C1\", \"rationale\": \"这一环在检索，直接交灰流丽\", \"confidence\": 0.8}");
        DuelDecision decision = rig.port().decide(optionalChainPoint(), DecisionDeadline.of(3000));
        assertInstanceOf(ChoiceIntent.ChainRespond.class, decision.intent(),
                "可选连锁应在候选里照模型的选择发出去");
    }

    private static DecisionPoint optionalChainPoint() {
        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.effectChoice(70, TestDuels.ENGAGE, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));
        return new DecisionPoint(31, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);
    }

    /** 捕获 EXEC_DECISION 的 missReasons（失败原因藏在旁路事件里）。 */
    private static final class ReasonCatcher implements DecisionTraceWriter {
        final List<String> reasons = new ArrayList<>();

        @Override
        public void append(DecisionPoint point, DuelDecision decision) {
        }

        @Override
        public void event(String kind, Map<String, Object> payload) {
            if ("EXEC_DECISION".equals(kind) && payload.get("missReasons") != null) {
                reasons.add(String.valueOf(payload.get("missReasons")));
            }
        }
    }

    private Rig rigWithTrace(ReasonCatcher tracer, String... replies) {
        PipelineSettings cfg = new PipelineSettings(true, settings().baseUrl(), settings().apiKey(),
                settings().model(), 0, 0, 20_000, 6, 0.80, 300,
                settings().plannerCallTimeoutMs(), settings().plannerCooldownMs(),
                settings().cardRagPath(), settings().judgeMaxTokens(), true, true);
        FakeChatModel chat = new FakeChatModel(replies);
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), cfg, trace);
        AtomicInteger calls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            calls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var port = new DecisionPipeline(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, cfg, TestDuels.facts(), delegate, tracer, trace);
        return new Rig(port, planner, chat, calls);
    }

    @Test
    void unparsableOneshotOutputIsReportedAsSuch() {
        ReasonCatcher tracer = new ReasonCatcher();
        Rig rig = rigWithTrace(tracer, "我觉得应该打对面");
        rig.port().decide(mainPoint(), DecisionDeadline.of(2000));
        assertTrue(tracer.reasons.stream().anyMatch(r -> r.contains("输出不可解析")),
                "要能区分'模型吐了非 JSON'与'超时'：" + tracer.reasons);
    }

    @Test
    void outOfRangeChoiceIsReportedAsSuch() {
        ReasonCatcher tracer = new ReasonCatcher();
        Rig rig = rigWithTrace(tracer, "{\"choice\": \"C99\", \"rationale\": \"乱选\", \"confidence\": 0.5}");
        rig.port().decide(mainPoint(), DecisionDeadline.of(2000));
        assertTrue(tracer.reasons.stream().anyMatch(r -> r.contains("选择越界")),
                "越界与解析失败必须分开：" + tracer.reasons);
    }

    @Test
    void chatFailureReportsTheCause() {
        ReasonCatcher tracer = new ReasonCatcher();
        Rig rig = rigWithTrace(tracer);
        rig.chat().fail = true;
        rig.port().decide(mainPoint(), DecisionDeadline.of(2000));
        assertTrue(tracer.reasons.stream().anyMatch(r -> r.contains("调用失败")),
                "调用失败要带上根因：" + tracer.reasons);
    }

    @Test
    void chatFailureFallsBackToDelegate() {
        Rig rig = rig();
        rig.chat().fail = true;
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.TACTICAL, decision.pathKind());
        assertEquals(1, rig.delegateCalls().get());
    }

    @Test
    void similarBranchMatchesWhenMainLineMisses() {
        String plan = """
                {"thinking": "t", "goal": "g",
                 "main_line": {"steps": [{"when": "MAIN1", "verb": "activate", "passcode": 999}],
                   "branches": [
                     {"if": {"description": "空场", "opp_monsters": "==0", "phase": "MAIN1"},
                      "steps": [{"when": "MAIN1", "verb": "summon", "passcode": 75982077, "why": "空场铺场"}]}
                   ]},
                 "confidence": 0.8}
                """;
        Rig rig = rig(plan);
        DecisionPoint point = mainPoint(); // 对方场面为空 → opp_monsters==0 成立
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(1000));
        assertEquals(PathKind.PLAN_BRANCH, decision.pathKind());
        assertEquals(ChoiceIntent.Summon.class, decision.intent().getClass());
    }

    @Test
    void similarSkippedUnderHighStakes() {
        // 我方 LP 2000（≤3000）→ 高 stakes → ③ 跳过 → ④ 直答
        Rig rig = rig(
                planJson("activate", 123456789L),
                "{\"choice\": \"C1\", \"rationale\": \"紧急\", \"confidence\": 0.7}");
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 2000, 8000);
        DecisionPoint point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001)));
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind());
    }

    /** 计划"迟到"的场景：慢速假模型（模拟真实规划调用耗时）。 */
    private static final class SlowModel implements ChatModel {
        private final long delayMs;
        private final String reply;

        SlowModel(long delayMs, String reply) {
            this.delayMs = delayMs;
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 先秒回 keep（锚点无计划），之后每次 直答 都要花 700ms。 */
    private static final class LateAnswerModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
            if (calls.getAndIncrement() == 0) {
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"keep\": true}")).build();
            }
            try {
                Thread.sleep(700); // 直答 本身要花 700ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder().aiMessage(
                    AiMessage.from("{\"choice\":\"C1\",\"rationale\":\"断检索\",\"confidence\":0.8}")).build();
        }
    }

    @Test
    void firstOneshotOfTurnWaitsLongerForThePlan() {
        // 本回合第一次要 直答 时计划还没发布：普通预算(800ms 的一半=400ms)很容易一次等不到
        // 就整回合放弃；加长后应该能拿到计划并走 ② 精确命中。
        PipelineSettings longer = new PipelineSettings(true, settings().baseUrl(), settings().apiKey(),
                settings().model(), 800, 3_000, 20_000, 6, 0.80, 300,
                settings().plannerCallTimeoutMs(), settings().plannerCooldownMs(),
                settings().cardRagPath(), settings().judgeMaxTokens(), true, true);
        AgentChatServices services = stack(trace, new SlowModel(600, planJson("summon", TestDuels.HORNET)));
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), longer, trace);
        DecisionEngine delegate = (point, deadline) -> {
            throw new AssertionError("不该落到 ⑤ 兜底");
        };
        var port = new DecisionPipeline(new ReflexPolicyImpl(),
                planner, new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, longer, TestDuels.facts(), delegate,
                DecisionTraceWriter.NOOP, trace);

        DecisionPoint point = mainPoint();
        planner.observePoint(point); // 触发锚点：异步生成计划（600ms）

        long start = System.currentTimeMillis();
        DuelDecision decision = port.decide(point, DecisionDeadline.of(4_000));
        long took = System.currentTimeMillis() - start;

        assertEquals(PathKind.PLAN_STEP, decision.pathKind(), "首动作应等到计划并精确接地");
        assertTrue(took >= 500, "应该真的等了计划，而不是立刻放弃：" + took + "ms");
    }

    @Test
    void planWaitIsOncePerTurnAndUniform() {
        PipelineSettings s = settings(); // waitPerDecision=4000, firstOneshotWait=7000
        // 不分阶段/请求类型，时长都一样
        assertEquals(7_000, DecisionPipeline.planWaitMs(false, s, 30_000));
        // 整回合只等一次
        assertEquals(0, DecisionPipeline.planWaitMs(true, s, 30_000));
        // 只受本回合等待额度约束（等待是额外时间，不再为 直答 预留）
        assertEquals(2_000, DecisionPipeline.planWaitMs(false, s, 2_000));

        PipelineSettings off = new PipelineSettings(true, s.baseUrl(), s.apiKey(), s.model(),
                0, 7_000, 20_000, 6, 0.80, 300, s.plannerCallTimeoutMs(), s.plannerCooldownMs(),
                s.cardRagPath(), s.judgeMaxTokens(), true, true);
        assertEquals(0, DecisionPipeline.planWaitMs(false, off, 30_000),
                "waitPerDecision=0 是显式关闭等待");
    }

    @Test
    void waitingForPlanDoesNotEatTheDecisionBudget() {
        PipelineSettings cfg = new PipelineSettings(true, settings().baseUrl(), settings().apiKey(),
                settings().model(), 150, 1_000, 20_000, 6, 0.80, 5_000,
                settings().plannerCallTimeoutMs(), settings().plannerCooldownMs(),
                settings().cardRagPath(), settings().judgeMaxTokens(), true, true);
        ChatModel chat = new LateAnswerModel();
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), cfg, trace);
        var port = new DecisionPipeline(
                new ReflexPolicyImpl(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, cfg, TestDuels.facts(), (p, d) -> {
                    throw new AssertionError("等待不该吃光预算，本应走 直答");
                }, DecisionTraceWriter.NOOP, trace);

        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var effect = TestDuels.effectChoice(40, TestDuels.ENGAGE, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));
        DecisionPoint point = new DecisionPoint(13, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);
        planner.observePoint(point);

        DuelDecision decision = port.decide(point, DecisionDeadline.of(1_200));

        assertEquals(PathKind.LLM_CALL, decision.pathKind(),
                "等完 1s 之后，直答 仍应拿到完整的 1.2s 预算");
    }

    @Test
    void firstOneshotOfTurnAlsoWaitsInAChainWindow() {
        // 用户口径：这个"首个"是指本回合第一次要 直答，不是第一次主阶段——
        // 对手回合开场的第一枪（连锁窗口）同样值得等计划。
        PipelineSettings cfg = new PipelineSettings(true, settings().baseUrl(), settings().apiKey(),
                settings().model(), 800, 3_000, 20_000, 6, 0.80, 300,
                settings().plannerCallTimeoutMs(), settings().plannerCooldownMs(),
                settings().cardRagPath(), settings().judgeMaxTokens(), true, true);
        String plan = "{\"thinking\": \"断检索\", \"goal\": \"保留资源\", \"main_line\": {\"steps\": ["
                + "{\"when\": \"CHAIN\", \"verb\": \"activate\", \"passcode\": " + TestDuels.ENGAGE
                + ", \"why\": \"这一环在赚卡\"}]}}";
        AgentChatServices services = stack(trace, new SlowModel(500, plan));
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), cfg, trace);
        var port = new DecisionPipeline(
                new ReflexPolicyImpl(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, cfg, TestDuels.facts(), (p, d) -> {
                    throw new AssertionError("不该落到 ⑤ 兜底");
                }, DecisionTraceWriter.NOOP, trace);

        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        // 用场上的卡：手牌响应的时机由对手决定，已刻意不让计划接地
        var effect = TestDuels.fieldEffectChoice(30, TestDuels.ENGAGE, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));
        DecisionPoint point = new DecisionPoint(12, 2, state.phase(),
                PlayerSide.OPPONENT, request, state,
                Candidates.from(request),
                "h", "r", RequestKind.CHAIN);
        planner.observePoint(point);

        DuelDecision decision = port.decide(point, DecisionDeadline.of(6_000));
        assertEquals(PathKind.PLAN_STEP, decision.pathKind(),
                "首次 直答 在连锁窗口里也应等到计划并接地");
    }

    @Test
    void ungroundedPlanIsAbandonedButKeepsItsTacticalIntent() {
        // 计划接不上地 → 路径作废（不再接地），但战术思想（thinking/goal）要留给 直答，
        // 否则决策器没有主线，容易打出与整体思路相反的操作。
        Rig rig = rig(fastWait(), planJson("activate", 123456789L), "{\"keep\": true}");
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        assertEquals(1, rig.planner().currentPlan().orElseThrow().steps().size());

        rig.port().decide(point, DecisionDeadline.of(2_000));

        TurnPlan skeleton = rig.planner().currentPlan().orElseThrow(
                () -> new AssertionError("作废后应保留一个只含战术思想的骨架计划"));
        assertTrue(skeleton.steps().isEmpty(), "步骤必须清空，避免继续拿失效路径接地");
        assertEquals("测试思路", skeleton.thinking(), "战术思想要保留");
        assertEquals("测试目标", skeleton.goal());
    }

    @Test
    void waitBudgetZeroSkipsPlannerWait() {
        PipelineSettings tight = new PipelineSettings(
                true, settings().baseUrl(), settings().apiKey(), settings().model(),
                0, 0, 20_000, 6, 0.80, 300, settings().plannerCallTimeoutMs(),
                settings().plannerCooldownMs(), settings().cardRagPath(), settings().judgeMaxTokens(), true, true);
        FakeChatModel chat = new FakeChatModel(planJson("activate", 123456789L)); // 计划不可接地
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), tight, trace);
        AtomicInteger delegateCalls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            delegateCalls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var port = new DecisionPipeline(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts(), new DuelMathService()),
                services, tight, TestDuels.facts(), delegate,
                DecisionTraceWriter.NOOP, trace);
        DecisionPoint point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests();

        chat.fail = true; // 直答 立即失败
        DuelDecision decision = port.decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.TACTICAL, decision.pathKind());
        assertEquals(1, delegateCalls.get());
    }

    @Test
    void plannerDegradesAfterChatFailure() {
        FakeChatModel chat = new FakeChatModel();
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), settings(), trace);
        DecisionPoint point = mainPoint();
        chat.fail = true;
        planner.observePoint(point);
        planner.drainForTests();
        assertTrue(planner.currentPlan().isEmpty());
        assertFalse(planner.healthy());
    }

    @Test
    void turnChangeRotatesSegment() {
        FakeChatModel chat = new FakeChatModel(planJson("activate", TestDuels.ENGAGE));
        AgentChatServices services = stack(trace, chat);
        PlannerSession planner = new PlannerSession(services, new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService()), TestDuels.facts(), settings(), trace);
        DecisionPoint point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests();
        assertEquals(2, planner.currentPlan().orElseThrow().turnNumber());

        var oppState = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var oppPoint = new DecisionPoint(2, 2, oppState.phase(),
                PlayerSide.SELF,
                new ChoiceRequest.ChainRequest(List.of()),
                oppState, List.of(), "h2", "r2",
                RequestKind.CHAIN);
        planner.observePoint(oppPoint);
        planner.drainForTests();
        assertTrue(chat.requests.size() >= 2, "换段应触发新锚点 checkpoint");
        assertTrue(VerboseLog.messageText(chat.requests.get(1).messages().getFirst()).contains("回合规划器"),
                "段轮换后 system 提示仍在消息首位");
    }
}
