package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelRecordFeed;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
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
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.model.Candidates;
import com.github.coldgust.ygoagent.pipeline.judge.JudgePrompts;
import com.github.coldgust.ygoagent.planner.PlannerPrompts;
import com.github.coldgust.ygoagent.planner.PlannerRuntime;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPipelineTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    /** 决策档（judge 用）：关思考 + 配置的思考强度覆盖。 */
    private static ChatOptions decisionTier(PipelineSettings cfg) {
        return ChatOptions.decision(cfg.judgeMaxTokens()).withReasoning(cfg.reasoningDecision());
    }

    /** 两个决策侧 agent 直接架在假模型上：解析/模板/记忆都走真实框架路径。 */
    private record Agents(PlannerAgent planner, JudgeAgent judge,
                          DuelChatMemory plannerMemory, DuelChatMemory judgeMemory) {
    }

    private Agents agents(FakeChatModel chat, PipelineSettings cfg) {
        ChatOptions decisionTier = decisionTier(cfg);
        var plannerMemory = newPlannerMemory();
        var judgeMemory = newJudgeMemory();
        DuelRecordFeed feed = new DuelRecordFeed(null, TestDuels.facts());
        feed.attach(plannerMemory);
        feed.attach(judgeMemory);
        return new Agents(
                new PlannerAgent(new ResilientChatModel(chat.baked(cfg.checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new JudgeAgent(new ResilientChatModel(chat.baked(decisionTier, false)),
                        judgeMemory, List.of(), trace),
                plannerMemory, judgeMemory);
    }

    private static DuelChatMemory newPlannerMemory() {
        return DuelChatMemory.forPlanner("test-planner",
                SystemMessage.from(PlannerPrompts.ADVISOR_SYSTEM), null);
    }

    private static DuelChatMemory newJudgeMemory() {
        return DuelChatMemory.forJudge("test-judge", SystemMessage.from(JudgePrompts.SYSTEM), null);
    }

    /** 测试用记录流：无记录端口（feed 只推我方应答行）。 */
    private static DuelRecordFeed feedFor(DuelChatMemory... memories) {
        DuelRecordFeed feed = new DuelRecordFeed(null, TestDuels.facts());
        for (DuelChatMemory memory : memories) {
            feed.attach(memory);
        }
        return feed;
    }

    private static PlannerRuntime plannerSession(Agents agents, ContextPackBuilder packer,
                                                 PipelineSettings cfg, VerboseLog trace) {
        return PlannerRuntime.builder(agents.planner(), packer, cfg, agents.plannerMemory())
                .feed(feedFor(agents.plannerMemory()))
                .trace(trace)
                .build();
    }

    private static PipelineSettings settings() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        return PipelineSettings.builder(d).build();
    }

    /** 幕僚建议 JSON（锚点/修订回复）。 */
    private static String advisorJson(String goal) {
        return "{\"strategy\": \"测试策略\", \"analysis\": \"测试场面\", \"goal\": \"" + goal
                + "\", \"tactics\": \"测试战术\", \"intel\": \"测试情报\", \"confidence\": 0.8}";
    }

    private static DecisionPoint mainPoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)),
                List.of(TestDuels.effectChoice(11, TestDuels.ENGAGE, 1001)));
    }

    private record Rig(DecisionPipeline port, PlannerRuntime planner, FakeChatModel chat,
                       AtomicInteger delegateCalls) {
    }

    /** 测试用：把"整回合等建议"压到很短，避免用例被 7s 等待拖慢。 */
    private static PipelineSettings fastWait() {
        return PipelineSettings.builder(settings())
                .waitPerDecisionMs(400)
                .firstAdviceWaitMs(400)
                .build();
    }

    private Rig rig(String... chatReplies) {
        return rig(settings(), chatReplies);
    }

    private Rig rig(PipelineSettings cfg, String... chatReplies) {
        FakeChatModel chat = new FakeChatModel(chatReplies);
        Agents agents = agents(chat, cfg);
        PlannerRuntime planner = plannerSession(agents, new ContextPackBuilder(TestDuels.facts()), cfg, trace);
        AtomicInteger delegateCalls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            delegateCalls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var port = DecisionPipeline.builder(new ReflexPolicyImpl(),
                planner,
                new ContextPackBuilder(TestDuels.facts()),
                agents.judge(), cfg, TestDuels.facts())
                .delegate(delegate)
                .traceWriter(DecisionTraceWriter.NOOP)
                .trace(trace)
                .judgeMemory(agents.judgeMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .build();
        return new Rig(port, planner, chat, delegateCalls);
    }

    @Test
    void reflexWinsBeforeAdvice() {
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
        // 规划器照常观察（回合锚点），但决策路径没有走 决策器判断：
        // 调用数 = 锚点 1 次（输出无效，keep 恢复已废止，无第二次），关键是"没有决策器判断"。
        assertTrue(rig.chat().requests.size() <= 3,
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

        assertFalse(decision.pathKind() == PathKind.ONLY_CHOICE, "可选连锁必须交给规划器/决策器判断");
        assertInstanceOf(ChoiceIntent.ChainDecline.class, decision.intent(),
                "模型选择 C0 时应落地为弃权");
    }

    @Test
    void advicePublishedThenJudgeDecides() {
        Rig rig = rig(advisorJson("先展开再压制"),
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}");
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        assertTrue(rig.planner().currentAdvice().isPresent(), "锚点 checkpoint 应产出建议");

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(1000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind());
        assertEquals(ChoiceIntent.Summon.class, decision.intent().getClass());
        assertEquals(0, rig.delegateCalls().get());
    }

    @Test
    void judgeReplanRetiresOldAdviceAndRepublishesWithReason() {
        // 2026-11-14 用户指令：决策器判决 needReplan=true → 发起重规划（原因转达规划器），
        // 旧建议即刻作废；本手决策照常给出。
        Rig rig = rig(fastWait(),
                advisorJson("先展开再压制"),
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8, "
                        + "\"needReplan\": true, \"replanReason\": \"主打线资源已被无效消耗\"}",
                advisorJson("改为保资源"));
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        String oldAdviceId = rig.planner().currentAdvice().orElseThrow().adviceId();

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind(), "重规划不影响本手决策");
        assertEquals(ChoiceIntent.Summon.class, decision.intent().getClass());

        rig.planner().drainForTests();
        var fresh = rig.planner().currentAdvice().orElseThrow();
        assertFalse(fresh.adviceId().equals(oldAdviceId), "重规划应发布新建议（旧建议已作废）");
        String plannerSaw = allUserTexts(rig);
        assertTrue(plannerSaw.contains("===重规划"), "规划器应收到重规划请求行");
        assertTrue(plannerSaw.contains("主打线资源已被无效消耗"),
                "决策器的重规划原因应原样转达给规划器");
    }

    @Test
    void replanIsCappedAtFivePerTurn() {
        // 每回合最多 5 次重规划（2026-11-15 用户指令）：前 5 次 needReplan 都触发重规划，
        // 第 6 次被配额闸挡下——不再作废建议、不再发重规划请求。
        String replanJudgeReply = "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8, "
                + "\"needReplan\": true, \"replanReason\": \"判决\"}";
        List<String> replies = new ArrayList<>(List.of(advisorJson("第一条建议")));
        for (int i = 1; i <= 5; i++) {
            replies.add(replanJudgeReply);              // 决策器判决 → 重规划 #i
            replies.add(advisorJson("重规划建议" + i));  // 重规划产出
        }
        replies.add(replanJudgeReply);                  // 第 6 次判决：应被配额挡下
        Rig rig = rig(fastWait(), replies.toArray(String[]::new));
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        for (int i = 0; i < 6; i++) {
            rig.port().decide(point, DecisionDeadline.of(3000));
            rig.planner().drainForTests();
        }
        // 只数每次 LLM 调用的"当前请求行"（最后一条 user 消息）：记忆里回放的历史
        // 重规划请求行不算，否则 5 次重规划会被数成 1+2+…+5=15
        long replanCalls = rig.chat().requests.stream()
                .map(r -> r.messages().stream()
                        .filter(m -> m instanceof UserMessage)
                        .map(m -> ((UserMessage) m).singleText())
                        .reduce((a, b) -> b)
                        .orElse(""))
                .filter(t -> t.contains("===重规划"))
                .count();
        assertEquals(5, replanCalls,
                "重规划请求行只应出现 5 次（第 6 次 needReplan 被配额闸挡下）");
    }

    /** 假模型收到的全部 user 消息文本（断言"规划器看到了什么"用）。 */
    private static String allUserTexts(Rig rig) {
        return rig.chat().requests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void invalidAdvisorOutputStillLetsJudgeDecide() {
        // 锚点输出无效（无实质建议内容）：② 决策器判断照常工作，建议缺席不阻塞
        Rig rig = rig("{}",
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}");
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();
        assertTrue(rig.planner().currentAdvice().isEmpty(), "锚点输出无效 → 无建议");

        DuelDecision decision = rig.port().decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, decision.pathKind());
        assertEquals(ChoiceIntent.Summon.class, decision.intent().getClass());
    }

    @Test
    void noDivergenceRequestAfterAdvisorOnly() {
        // 顾问唯一形态后不存在"抢答/发散"路径：任何请求里都不该出现 ===发散 模板
        Rig rig = rig(advisorJson("先展开"),
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}");
        DecisionPoint point = mainPoint();
        rig.planner().observePoint(point);
        rig.planner().drainForTests();

        rig.port().decide(point, DecisionDeadline.of(3000));
        rig.planner().drainForTests();
        assertTrue(rig.chat().requests.stream()
                        .flatMap(r -> r.messages().stream())
                        .noneMatch(m -> VerboseLog.messageText(m).contains("===发散")),
                "顾问模式不应有紧急决策（发散）请求");
    }

    @Test
    void judgeZoneInTheSameAnswerIsHonouredByTheFollowingPlaceRequest() {
        // 模型在决策器判断里选动作时一并给出 zone（同一次回答）→ 位置请求直接兑现，不再问
        Rig rig = rig(fastWait(),
                advisorJson("先展开"),
                "{\"choice\": \"C1\", \"zone\": \"MMZ-2\", \"rationale\": \"站中间列\", \"confidence\": 0.8}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();

        DuelDecision summon = rig.port().decide(main, DecisionDeadline.of(3000));
        assertEquals(PathKind.LLM_CALL, summon.pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        // "不再问"由路径种类兜底：位置若没被兑现，这里会是 LLM_CALL/TACTICAL 而非 ZONE_HINT。
        assertEquals(PathKind.ZONE_HINT, place.pathKind(), "位置应由决策器同一次回答给出并兑现");
        assertEquals(List.of("C2"), ((ChoiceIntent.Place) place.intent()).zones().stream()
                .map(id -> id.value()).toList(), "模型给 MMZ-2，就该提交 C2");
    }

    @Test
    void illegalJudgeZoneFallsBackToTheFirstZone() {
        // 模型给的格子不可放（不在这批候选里）→ 视为没给，取第一个
        Rig rig = rig(fastWait(),
                advisorJson("先展开"),
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
    void placeRequestWithoutAnyHintFallsBackToFirstZoneWithoutAskingLlm() {
        // 模型没给位置：直接取第一个可放置区域，不额外发一次请求问位置
        Rig rig = rig(fastWait(),
                advisorJson("先展开"),
                "{\"choice\": \"C1\", \"rationale\": \"召唤大黄蜂\", \"confidence\": 0.8}");
        DecisionPoint main = mainPoint();
        rig.planner().observePoint(main);
        rig.planner().drainForTests();

        DuelDecision summon = rig.port().decide(main, DecisionDeadline.of(1000));
        assertEquals(PathKind.LLM_CALL, summon.pathKind());

        DuelDecision place = rig.port().decide(placePoint(), DecisionDeadline.of(1000));
        // ONLY_CHOICE = ① 唯一合法直接答，没有为位置发起任何 LLM 决策调用
        assertEquals(PathKind.ONLY_CHOICE, place.pathKind(), "没给位置就走规则兜底，不问 LLM");
        assertEquals(List.of("C1"), ((ChoiceIntent.Place) place.intent()).zones().stream()
                .map(id -> id.value()).toList(), "兜底取第一个可放置区域");
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

    private static DecisionPoint placePoint() {
        var state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.PlaceRequest(1, List.of(
                ZoneRef.mainMonster(PlayerSide.SELF, 0), ZoneRef.mainMonster(PlayerSide.SELF, 1)));
        return new DecisionPoint(6, 2, state.phase(), PlayerSide.SELF, request, state,
                Candidates.from(request), "h", "r", RequestKind.PLACE);
    }

    /** 捕获 EXEC_DECISION 的 missReasons（失败原因藏在旁路事件里）。 */
    private static final class ReasonCatcher implements DecisionTraceWriter {
        final List<String> reasons = new ArrayList<>();

        @Override
        public void append(DecisionPoint point, DuelDecision decision) {
        }

        @Override
        public void event(String kind, Map<String, Object> payload) {
            if ("EXEC_DECISION".equals(kind)) {
                reasons.add(String.valueOf(payload.get("missReasons")));
            }
        }
    }

    @Test
    void unparsableJudgeOutputIsReportedAsSuch() {
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
        assertTrue(tracer.reasons.stream().anyMatch(r -> r.contains("越界")),
                "越界要有独立记因（它不是端点故障）：" + tracer.reasons);
    }

    @Test
    void chatFailureReportsTheCause() {
        ReasonCatcher tracer = new ReasonCatcher();
        Rig rig = rigWithTrace(tracer, "{\"choice\": \"C1\", \"confidence\": 0.5}");
        rig.chat().fail = true;
        rig.port().decide(mainPoint(), DecisionDeadline.of(2000));
        assertTrue(tracer.reasons.stream().anyMatch(r -> r.contains("调用失败")),
                "调用失败要有独立记因（它不是模型行为问题）：" + tracer.reasons);
    }

    private Rig rigWithTrace(ReasonCatcher tracer, String... replies) {
        PipelineSettings cfg = PipelineSettings.builder(settings())
                .waitPerDecisionMs(0).firstAdviceWaitMs(0)
                .waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
        FakeChatModel chat = new FakeChatModel(replies);
        Agents agents = agents(chat, cfg);
        PlannerRuntime planner = plannerSession(agents, new ContextPackBuilder(TestDuels.facts()), cfg, trace);
        AtomicInteger calls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            calls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var port = DecisionPipeline.builder(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                agents.judge(), cfg, TestDuels.facts())
                .delegate(delegate)
                .traceWriter(tracer)
                .trace(trace)
                .judgeMemory(agents.judgeMemory())
                .feed(feedFor(agents.plannerMemory(), agents.judgeMemory()))
                .build();
        return new Rig(port, planner, chat, calls);
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

    /** 慢速假模型（模拟真实调用耗时），回复固定。 */
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

    /** 每次都要花 700ms 才给出选择（模拟真实决策调用耗时）。 */
    private static final class SlowJudgeModel implements ChatModel {
        @Override
        public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(
                    "{\"choice\":\"C1\",\"rationale\":\"断检索\",\"confidence\":0.8}")).build();
        }
    }

    @Test
    void firstJudgeOfTurnWaitsLongerForTheAdvice() {
        // 本回合第一次要决策器判断时建议还没发布：普通预算(800ms)很容易一次等不到
        // 就整回合放弃；加长后应该能拿到建议并在快照里带 [规划器情报]。
        PipelineSettings longer = PipelineSettings.builder(settings())
                .waitPerDecisionMs(800).firstAdviceWaitMs(3_000)
                .waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
        var plannerMemory = newPlannerMemory();
        PlannerAgent plannerAgent = new PlannerAgent(
                new ResilientChatModel(new SlowModel(600, advisorJson("先展开再压制"))),
                plannerMemory, List.of(), trace);
        PlannerRuntime planner = PlannerRuntime.builder(plannerAgent,
                new ContextPackBuilder(TestDuels.facts()), longer, plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        DecisionEngine delegate = (point, deadline) -> {
            throw new AssertionError("不该落到 ③ 兜底");
        };
        var judgeMemory = newJudgeMemory();
        var port = DecisionPipeline.builder(new ReflexPolicyImpl(),
                planner, new ContextPackBuilder(TestDuels.facts()),
                new JudgeAgent(new ResilientChatModel(new SlowJudgeModel()), judgeMemory, List.of(), trace),
                longer, TestDuels.facts())
                .delegate(delegate)
                .traceWriter(DecisionTraceWriter.NOOP)
                .trace(trace)
                .judgeMemory(judgeMemory)
                .feed(feedFor(plannerMemory, judgeMemory))
                .build();

        DecisionPoint point = mainPoint();
        planner.observePoint(point); // 触发锚点：异步产出建议（600ms）

        long start = System.currentTimeMillis();
        DuelDecision decision = port.decide(point, DecisionDeadline.of(4_000));
        long took = System.currentTimeMillis() - start;

        assertEquals(PathKind.LLM_CALL, decision.pathKind(), "首动作应等到建议并由决策器判断");
        assertTrue(took >= 500, "应该真的等了建议，而不是立刻放弃：" + took + "ms");
        assertTrue(planner.currentAdvice().isPresent(), "等待期间建议已发布");
    }

    @Test
    void adviceWaitIsOncePerTurnAndUniform() {
        PipelineSettings s = settings(); // waitPerDecision=4000, firstAdviceWait=7000
        // 不分阶段/请求类型，时长都一样
        assertEquals(7_000, DecisionPipeline.adviceWaitMs(false, s, 30_000));
        // 整回合只等一次
        assertEquals(0, DecisionPipeline.adviceWaitMs(true, s, 30_000));
        // 只受本回合等待额度约束（等待是额外时间，不再为决策器预留）
        assertEquals(2_000, DecisionPipeline.adviceWaitMs(false, s, 2_000));

        PipelineSettings off = PipelineSettings.builder(s)
                .waitPerDecisionMs(0).waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
        assertEquals(0, DecisionPipeline.adviceWaitMs(false, off, 30_000),
                "waitPerDecision=0 是显式关闭等待");
    }

    @Test
    void waitingForAdviceDoesNotEatTheDecisionBudget() {
        PipelineSettings cfg = PipelineSettings.builder(settings())
                .waitPerDecisionMs(150).firstAdviceWaitMs(1_000)
                .waitPerTurnMs(20_000).judgeTimeoutMs(5_000)
                .build();
        var plannerMemory = newPlannerMemory();
        PlannerAgent plannerAgent = new PlannerAgent(
                new ResilientChatModel(new LateKeepModel()),
                plannerMemory, List.of(), trace);
        PlannerRuntime planner = PlannerRuntime.builder(plannerAgent,
                new ContextPackBuilder(TestDuels.facts()), cfg, plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        var judgeMemory = newJudgeMemory();
        var port = DecisionPipeline.builder(
                new ReflexPolicyImpl(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                new JudgeAgent(new ResilientChatModel(new SlowJudgeModel()), judgeMemory, List.of(), trace),
                cfg, TestDuels.facts())
                .delegate((p, d) -> {
                    throw new AssertionError("等待不该吃光预算，本应走 决策器判断");
                })
                .traceWriter(DecisionTraceWriter.NOOP)
                .trace(trace)
                .judgeMemory(judgeMemory)
                .feed(feedFor(plannerMemory, judgeMemory))
                .build();

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
                "等完 1s 之后，决策器判断 仍应拿到完整的 1.2s 预算");
    }

    @Test
    void firstJudgeOfTurnAlsoWaitsInAChainWindow() {
        // 用户口径：这个"首个"是指本回合第一次要决策器判断，不是第一次主阶段——
        // 对手回合开场的第一枪（连锁窗口）同样值得等建议。
        PipelineSettings cfg = PipelineSettings.builder(settings())
                .waitPerDecisionMs(800).firstAdviceWaitMs(3_000)
                .waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
        var plannerMemory = newPlannerMemory();
        PlannerAgent plannerAgent = new PlannerAgent(
                new ResilientChatModel(new SlowModel(500, advisorJson("保资源，抓检索时点"))),
                plannerMemory, List.of(), trace);
        PlannerRuntime planner = PlannerRuntime.builder(plannerAgent,
                new ContextPackBuilder(TestDuels.facts()), cfg, plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        var judgeMemory = newJudgeMemory();
        var port = DecisionPipeline.builder(
                new ReflexPolicyImpl(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                new JudgeAgent(new ResilientChatModel(new SlowJudgeModel()), judgeMemory, List.of(), trace),
                cfg, TestDuels.facts())
                .delegate((p, d) -> {
                    throw new AssertionError("不该落到 ③ 兜底");
                })
                .traceWriter(DecisionTraceWriter.NOOP)
                .trace(trace)
                .judgeMemory(judgeMemory)
                .feed(feedFor(plannerMemory, judgeMemory))
                .build();

        var state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
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
        assertEquals(PathKind.LLM_CALL, decision.pathKind(),
                "首次决策器判断在连锁窗口里也应等到建议");
    }

    /** 先秒回空 JSON（无建议可发），锚点路径不产出建议。 */
    private static final class LateKeepModel implements ChatModel {
        @Override
        public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
        }
    }

    @Test
    void waitBudgetZeroSkipsAdviceWait() {
        PipelineSettings tight = PipelineSettings.builder(settings())
                .waitPerDecisionMs(0).firstAdviceWaitMs(0)
                .waitPerTurnMs(20_000).judgeTimeoutMs(300)
                .build();
        FakeChatModel chat = new FakeChatModel(advisorJson("先展开"));
        var plannerMemory = newPlannerMemory();
        PlannerRuntime planner = PlannerRuntime.builder(
                new PlannerAgent(new ResilientChatModel(chat.baked(tight.checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new ContextPackBuilder(TestDuels.facts()),
                tight, plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        AtomicInteger delegateCalls = new AtomicInteger();
        DecisionEngine delegate = (point, deadline) -> {
            delegateCalls.incrementAndGet();
            return new DuelDecision(new ChoiceIntent.EndTurn(), PathKind.TACTICAL, 0.5,
                    DecisionExplanation.of("delegate"),
                    Timing.ZERO);
        };
        var judgeMemory = newJudgeMemory();
        var port = DecisionPipeline.builder(point -> Optional.empty(), planner,
                new ContextPackBuilder(TestDuels.facts()),
                new JudgeAgent(new ResilientChatModel(chat.baked(decisionTier(tight), false)),
                        judgeMemory, List.of(), trace),
                tight, TestDuels.facts())
                .delegate(delegate)
                .traceWriter(DecisionTraceWriter.NOOP)
                .trace(trace)
                .judgeMemory(judgeMemory)
                .feed(feedFor(plannerMemory, judgeMemory))
                .build();
        DecisionPoint point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests();

        chat.fail = true; // 决策器判断 立即失败
        DuelDecision decision = port.decide(point, DecisionDeadline.of(3000));
        assertEquals(PathKind.TACTICAL, decision.pathKind());
        assertEquals(1, delegateCalls.get());
    }

    @Test
    void plannerDegradesAfterChatFailure() {
        FakeChatModel chat = new FakeChatModel();
        var plannerMemory = newPlannerMemory();
        PlannerRuntime planner = PlannerRuntime.builder(
                new PlannerAgent(new ResilientChatModel(chat.baked(settings().checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new ContextPackBuilder(TestDuels.facts()),
                settings(), plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        DecisionPoint point = mainPoint();
        chat.fail = true;
        planner.observePoint(point);
        planner.drainForTests();
        assertTrue(planner.currentAdvice().isEmpty());
        assertFalse(planner.healthy());
    }

    @Test
    void turnChangeRotatesSegment() {
        FakeChatModel chat = new FakeChatModel(advisorJson("先展开"));
        var plannerMemory = newPlannerMemory();
        PlannerRuntime planner = PlannerRuntime.builder(
                new PlannerAgent(new ResilientChatModel(chat.baked(settings().checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new ContextPackBuilder(TestDuels.facts()),
                settings(), plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
        DecisionPoint point = mainPoint();
        planner.observePoint(point);
        planner.drainForTests();
        assertEquals(2, planner.currentAdvice().orElseThrow().turn());

        var oppState = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var oppPoint = new DecisionPoint(2, 2, oppState.phase(),
                PlayerSide.SELF,
                new ChoiceRequest.ChainRequest(List.of()),
                oppState, List.of(), "h2", "r2",
                RequestKind.CHAIN);
        planner.observePoint(oppPoint);
        planner.drainForTests();
        assertTrue(chat.requests.size() >= 2, "换段应触发新锚点 checkpoint");
        assertTrue(VerboseLog.messageText(chat.requests.get(1).messages().getFirst()).contains("战术幕僚"),
                "段轮换后 system 提示仍在消息首位");
    }

    /** 第 3 回合我方主阶段的空连锁决策点（测换回合作废/段轮换用）。 */
    private static DecisionPoint turn3Point() {
        var state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return new DecisionPoint(14, 3, state.phase(),
                PlayerSide.SELF,
                new ChoiceRequest.ChainRequest(List.of()),
                state, List.of(), "h3", "r3",
                RequestKind.CHAIN);
    }

    /** 直接架 PlannerRuntime（不经决策管线），返回可用实例。 */
    private PlannerRuntime barePlanner(FakeChatModel chat) {
        var plannerMemory = newPlannerMemory();
        return PlannerRuntime.builder(
                new PlannerAgent(new ResilientChatModel(chat.baked(settings().checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new ContextPackBuilder(TestDuels.facts()),
                settings(), plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();
    }

    @Test
    void turnSwitchRetiresPreviousAdviceForTheNewTurn() {
        // 建议生命周期 = 回合（2026-11-15）：换回合 observePoint 即作废旧建议（决策线程），
        // 新回合首手 currentAdvice 为空 → 决策管线走 waitForAdvice 等本回合锚点，
        // 决策器不再拿上回合情报判 needReplan（消灭回合边界双规划的根源）。
        FakeChatModel chat = new FakeChatModel(advisorJson("T2 建议"), advisorJson("T3 建议"));
        PlannerRuntime planner = barePlanner(chat);

        planner.observePoint(mainPoint());
        planner.drainForTests();
        assertTrue(planner.currentAdvice().isPresent(), "T2 锚点已发布建议");

        planner.observePoint(turn3Point());
        assertTrue(planner.currentAdvice().isEmpty(),
                "换回合应立即作废上回合建议——新回合首手等本回合锚点");

        planner.drainForTests();
        assertEquals(3, planner.currentAdvice().orElseThrow().turn(), "T3 锚点发布本回合建议");
    }

    @Test
    void replanQueuedForOldTurnIsDroppedWhenTurnRotates() {
        // 回合末手的 needReplan 判决：重规划任务已排上 worker 但未执行；新回合 observePoint
        // 先行轮换段号 → 旧回合重规划在 segment 校验处自弃（规划的是已结束的回合），
        // 不再与 T3 锚点背靠背双规划。
        // 时序确定性：模型调用带 300ms 延迟，主线程在 T2 锚点执行期间完成
        // requestReplan（任务排队）→ observePoint(T3)（段号轮换），轮换必然先于重规划任务出队。
        FakeChatModel chat = new FakeChatModel(advisorJson("锚点建议"));
        ChatModel slowRecording = new ChatModel() {
            @Override
            public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return chat.chat(request);
            }
        };
        var plannerMemory = newPlannerMemory();
        PlannerRuntime planner = PlannerRuntime.builder(
                new PlannerAgent(new ResilientChatModel(
                        FakeChatModel.baked(slowRecording, settings().checkpointOptions(), true)),
                        plannerMemory, List.of(), trace),
                new ContextPackBuilder(TestDuels.facts()),
                settings(), plannerMemory)
                .feed(feedFor(plannerMemory))
                .trace(trace)
                .build();

        planner.observePoint(mainPoint());      // worker：T2 锚点开始（300ms）
        planner.requestReplan(mainPoint(), "上回合规划已过时"); // 回合末手判决：任务排队、未执行
        planner.observePoint(turn3Point());     // 决策线程：作废旧建议 + 段号轮换，T3 锚点排队
        planner.drainForTests();

        var advice = planner.currentAdvice().orElseThrow();
        assertEquals(3, advice.turn(), "只有 T3 锚点发布建议");
        assertEquals(2, chat.requests.size(), "旧回合重规划应被丢弃：只有 T2/T3 两次锚点调用");
        String plannerSaw = chat.requests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .collect(Collectors.joining("\n"));
        assertFalse(plannerSaw.contains("===重规划"), "旧回合的重规划不应发出请求行");
    }
}
