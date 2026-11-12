package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.pipeline.plan.ActionSpec;
import com.github.coldgust.ygoagent.context.DecisionMemory;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.Candidates;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.plan.PlanStep;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.pipeline.TestDuels;

/**
 * 锚点渲染的战场计数（回归：只盖了 3 张，规划器却读到 6 张）。
 */
class ContextPackBuilderTest {

    private final CardFacts facts = TestDuels.facts();
    private final ContextPackBuilder builder = new ContextPackBuilder(facts, new DuelMathService());

    private static CardInstance backrow(long id, CardIdentity identity, int seq, Position pos) {
        return new CardInstance(id, identity, ZoneRef.spellTrap(PlayerSide.SELF, seq), pos,
                Map.of(), List.of());
    }

    @Test
    void sameSlotDuplicatesAreCountedOnce() {
        // 旧镜像会给每张盖卡留下"已知身份 + 未知身份"两个实例，同处一格
        List<CardInstance> backrow = List.of(
                backrow(1, TestDuels.known(TestDuels.ENGAGE), 0, Position.FACE_DOWN_DEFENSE),
                backrow(2, CardIdentity.UNKNOWN, 0, Position.FACE_DOWN_DEFENSE),
                backrow(3, TestDuels.known(TestDuels.WIDOW_ANCHOR), 1, Position.FACE_DOWN_DEFENSE),
                backrow(4, CardIdentity.UNKNOWN, 1, Position.FACE_DOWN_DEFENSE),
                backrow(5, TestDuels.known(TestDuels.HORNET), 2, Position.FACE_DOWN_DEFENSE),
                backrow(6, CardIdentity.UNKNOWN, 2, Position.FACE_DOWN_DEFENSE));

        DuelState state = TestDuels.state(2, true, List.of(), backrow, List.of(), List.of(), 8000, 8000);

        assertEquals(3, state.spellTraps(PlayerSide.SELF).size());
        assertEquals(3, new DuelMathService().constraints(state, facts).get("selfBackrow"));

        String anchor = builder.anchorPack(state);
        assertFalse(anchor.contains("盖卡、盖卡、盖卡、盖卡"), "锚点里不该出现重复渲染:\n" + anchor);
        assertTrue(anchor.contains("闪刀起动-交闪(25048511)(里侧)"), "我方盖卡应带名字:\n" + anchor);
        assertEquals(3, occurrences(anchor, "(里侧)"), "三张盖卡就渲染三条:\n" + anchor);
    }

    @Test
    void boardSlotsListOccupancyPerCellWithCodes() {
        // 位置要成为决策，模型必须知道每格站着什么——只给"怪兽区"三个字它没法写 MMZ-3
        List<CardInstance> monsters = List.of(new CardInstance(1, TestDuels.known(TestDuels.HORNET),
                ZoneRef.mainMonster(PlayerSide.SELF, 2), Position.FACE_UP_ATTACK, Map.of(), List.of()));
        List<CardInstance> backrow = List.of(
                backrow(2, TestDuels.known(TestDuels.ENGAGE), 0, Position.FACE_DOWN_DEFENSE));
        DuelState state = TestDuels.state(2, true, monsters, backrow, List.of(), List.of(), 8000, 8000);

        String anchor = builder.anchorPack(state);
        assertTrue(anchor.contains("[场地槽位]"), "锚点要有逐格占用:\n" + anchor);
        assertTrue(anchor.contains("3大黄蜂浮游单元"), "第 3 格应显示卡名:\n" + anchor);
        assertTrue(anchor.contains("MMZ: 1空 2空 3"), "空格要标成空，模型才知道能放哪:\n" + anchor);
        assertTrue(anchor.contains("STZ: 1闪刀起动-交闪"), "魔陷区也要逐格:\n" + anchor);
        assertTrue(anchor.contains("FZ: 空"), "场地魔法区不能漏:\n" + anchor);
    }

    @Test
    void placePackListsZonesWithCodes() {
        // 位置不再单独发一次请求，但包一旦构造出来，候选仍要写清归属+编码（兜底/排查用）
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.PlaceRequest(1, List.of(
                ZoneRef.mainMonster(PlayerSide.SELF, 0), ZoneRef.mainMonster(PlayerSide.SELF, 1)));
        DecisionPoint point = new DecisionPoint(3, 2, state.phase(), PlayerSide.SELF, request, state,
                Candidates.from(request), "h", "r", RequestKind.PLACE);

        String pack = builder.decisionPack(point);
        assertTrue(pack.contains("C1 我方主怪兽区#1（MMZ-1）"), "候选要写清归属+编码:\n" + pack);
        assertTrue(pack.contains("[场地槽位]"), "位置需要占用信息:\n" + pack);
    }

    @Test
    void planStepRendersItsZoneSoOneShotCanHonourIt() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        DecisionPoint point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(10, TestDuels.HORNET)), List.of());
        PlanStep step = new PlanStep(0,
                RequestKind.MAIN_PHASE,
                List.of(new ActionSpec("summon", TestDuels.HORNET,
                        ZoneRef.mainMonster(PlayerSide.SELF, 2))),
                Map.of(), Map.of("why", "抢纵列"), 0.8);
        TurnPlan plan = new TurnPlan("p1", 2, "先展开", "铺场", List.of(step), List.of(), 0, 0.8);

        String pack = builder.decisionPack(point, plan);
        assertTrue(pack.contains("→ MMZ-3"), "计划里的位置要跟着显示:\n" + pack);
    }

    @Test
    void anchorNoLongerCarriesLethalVerdict() {
        // 回合开始时"空场 → 不可斩杀"没有信息量，已从锚点摘除
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        String anchor = builder.anchorPack(state);

        assertFalse(anchor.contains("斩杀"), "锚点里不该再有斩杀判定:\n" + anchor);
        assertTrue(anchor.contains("[约束]"), "确定性计数要保留:\n" + anchor);
        assertTrue(anchor.contains("selfBackrow=0"), "计数应仍然可用:\n" + anchor);
    }

    @Test
    void faceUpBackrowIsNotRenderedAsSet() {
        List<CardInstance> backrow = List.of(
                backrow(1, TestDuels.known(TestDuels.ENGAGE), 0, Position.FACE_UP_ATTACK));

        DuelState state = TestDuels.state(2, true, List.of(), backrow, List.of(), List.of(), 8000, 8000);
        String anchor = builder.anchorPack(state);

        assertTrue(anchor.contains("闪刀起动-交闪(25048511)"), "表侧魔陷要有名字:\n" + anchor);
        assertFalse(anchor.contains("里侧"), "表侧魔陷不能被说成里侧:\n" + anchor);
        assertFalse(anchor.contains("朝向未知"), "朝向已知就不该标未知:\n" + anchor);
    }

    @Test
    void opponentUnknownBackrowIsAnAnonymousSetCard() {
        CardInstance set = new CardInstance(11, CardIdentity.UNKNOWN,
                ZoneRef.spellTrap(PlayerSide.OPPONENT, 0), Position.FACE_DOWN_DEFENSE, Map.of(), List.of());

        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(set), 8000, 8000);
        String anchor = builder.anchorPack(state);

        assertTrue(anchor.contains("盖卡"), "对方盖卡只应显示为盖卡:\n" + anchor);
        assertEquals(1, new DuelMathService().constraints(state, facts).get("opponentBackrow"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    @Test
    void effectYesNoOffersBothYesAndNo() {
        // 回归："是否发动"必须有 C1 可发动的候选，否则执行器看到空候选直接走兜底
        var choice = new ChoiceRequest.CardChoice(
                41, TestDuels.known(TestDuels.ENGAGE), ZoneRef.hand(PlayerSide.SELF));
        var request = new ChoiceRequest.EffectYesNoRequest(choice, 1001);
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var candidates = Candidates.from(request);
        var point = new DecisionPoint(
                5, 1, state.phase(), PlayerSide.SELF, request, state, candidates, "h", "r",
                RequestKind.EFFECT_YES_NO);

        assertEquals(2, candidates.size(), "应有'发动'与'不发动'两个候选（弃权也是真候选）");
        String section = builder.candidatesSection(point);
        assertTrue(section.contains("发动：C1"), "要给出可发动的候选:\n" + section);
        assertTrue(section.contains("不发动"), "也要给出不发动:\n" + section);
        assertFalse(section.contains("C0"), "不再有伪候选 C0，弃权项自己带编号:\n" + section);
    }

    @Test
    void chainSectionShowsEveryLinkForcedMarksAndDeclineOption() {
        // 连锁决策要能看清"现在打断的是哪一环"、哪些必发、以及可以弃权
        var choice = new ChoiceRequest.CardChoice(
                31, TestDuels.known(TestDuels.ENGAGE),
                ZoneRef.spellTrap(PlayerSide.SELF, 0));
        var effect = new ChoiceRequest.EffectChoice(choice, 1001);
        var request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        effect, false)));

        DuelState base = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        DuelState withChain = new DuelState(base.duelRule(), base.turnNumber(), base.turnPlayer(),
                base.phase(), base.players(), base.cards(),
                List.of(new ChainNode(1, TestDuels.known(TestDuels.HORNET),
                        1001, ZoneRef.mainMonster(PlayerSide.OPPONENT, 0), PlayerSide.OPPONENT)),
                base.result(), base.selfSeat());
        var point = new DecisionPoint(
                7, 2, withChain.phase(), PlayerSide.OPPONENT, request, withChain,
                Candidates.from(request), "h", "r",
                RequestKind.CHAIN);

        String section = builder.candidatesSection(point);

        assertTrue(section.contains("当前连锁（1 环"), "要逐环列出当前连锁:\n" + section);
        assertTrue(section.contains("对方"), "要标出这一环是谁发动的:\n" + section);
        assertTrue(section.contains("可连锁"), "要列出可连锁的效果:\n" + section);
        assertTrue(section.contains("不响应（弃权）"), "必须给出弃权选项:\n" + section);
        assertTrue(section.contains("弃权项：C"), "弃权项要带编号（不再是伪候选 C0）:\n" + section);
        assertTrue(section.contains("选发"), "要说明是选发而非必发:\n" + section);
        assertTrue(section.contains("均已通过规则校验"), "要声明候选已合法，别让模型自己验条件:\n" + section);
    }

    @Test
    void judgePackCarriesCurrentPlanWithCursorAndBranches() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());

        TurnPlan plan = new TurnPlan("P2:SELF#1", 2, "先用抓锚解场再铺场", "用最少手牌打断对手展开",
                List.of(
                        step(0, "summon", TestDuels.HORNET, "通召做展开起点"),
                        step(1, "activate", TestDuels.WIDOW_ANCHOR, "抓锚解对手怪兽"),
                        step(2, "end_turn", 0, "留手牌过")),
                List.of(new TurnPlan.TurnBranch("对手检索",
                        Map.of("phase", "MAIN1"),
                        List.of(step(100, "activate", TestDuels.ENGAGE, "交闪先赚一张")))),
                1, 0.8);

        String pack = builder.decisionPack(point, plan);

        assertTrue(pack.contains("[本回合计划] P2:SELF#1"), "直答 要看到计划编号:\n" + pack);
        assertTrue(pack.contains("进度 1/3"), "要给出计划进度:\n" + pack);
        assertTrue(pack.contains("目标：用最少手牌打断对手展开"), "要给出目标:\n" + pack);
        assertTrue(pack.contains("✓ 1. summon 大黄蜂浮游单元"), "已完成步要打勾:\n" + pack);
        assertTrue(pack.contains("▶ 2. activate 闪刀机-黑寡妇抓锚"), "当前步要打 ▶:\n" + pack);
        assertTrue(pack.contains("· 3. end_turn"), "待办步要区分:\n" + pack);
        assertTrue(pack.contains("偏离计划第 N 步"), "要允许偏离但要求说明理由:\n" + pack);
        assertTrue(pack.contains("分支：") && pack.contains("对手检索"), "分支也要带上:\n" + pack);

        // ▶ 步是 MAIN_PHASE，当前决策点也是 MAIN_PHASE → 不该出现"计划未覆盖"的提示
        assertFalse(pack.contains("计划未覆盖这个窗口"), "同类决策点不该提示未覆盖:\n" + pack);

        // 换到连锁窗口：计划没覆盖，必须明确告诉模型别硬套
        var chainPoint = new DecisionPoint(
                9, 2, state.phase(), PlayerSide.OPPONENT,
                new ChoiceRequest.ChainRequest(List.of()),
                state, Candidates.from(
                        new ChoiceRequest.ChainRequest(List.of())),
                "h", "r", RequestKind.CHAIN);
        String chainPack = builder.decisionPack(chainPoint, plan);
        assertTrue(chainPack.contains("计划未覆盖这个窗口"), "类型不一致要说明:\n" + chainPack);

        // 没有计划（规划器未就绪）时，退化为纯局面决策，不能凭空造出计划段
        assertFalse(builder.decisionPack(point).contains("[本回合计划]"), "无计划时不该出现计划段");
    }

    @Test
    void deckStrategyIsInjectedFirstAndActsAsGuidance() {
        // 用户在界面里写的打法：在静态资料段最前面，且带"冲突时以此为准"的措辞，
        // 否则模型会把它当成普通参考资料忽略掉。
        ContextPackBuilder withStrategy = new ContextPackBuilder(facts, new DuelMathService(),
                "闪刀是资源型卡组：先用交闪赚卡，别为了打点把场铺满；\n"
                        + "墓地魔法不足 3 张时不要发抓锚的抢控制权效果。");
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());

        String anchor = withStrategy.anchorPack(state);   // 兼容入口 = 静态 + 动态
        assertTrue(anchor.startsWith("[卡组思路]"), "思路要放在最前面:\n" + anchor);
        assertTrue(anchor.contains("别为了打点把场铺满"), "正文要原样带进去:\n" + anchor);
        assertTrue(anchor.contains("以此为准"), "要说清它是优先级更高的依据:\n" + anchor);

        // 直答的决策包只带动态内容；静态资料由调用方作为独立消息放在前面（前缀缓存）
        String pack = withStrategy.decisionPack(point);
        assertFalse(pack.contains("[卡组思路]"), "决策包不再携带静态资料:\n" + pack);
        assertTrue(pack.startsWith("[局面]"), "动态包以局面开头:\n" + pack);
        String combined = withStrategy.judgeStaticMaterial() + pack;
        assertTrue(combined.indexOf("[卡组思路]") < combined.indexOf("[局面]"),
                "模型阅读顺序仍是思路在前、局面在后:\n" + combined);
        // 连招参考与卡组清单是规划器专属；核心资料（含思路）两边都有
        assertTrue(withStrategy.plannerStaticMaterial().contains("[卡组思路]"));
        // 规划器静态消息独立成整局资料（跨回合前缀缓存），锚点只剩易变部分
        assertTrue(withStrategy.plannerStaticMaterial().startsWith("[整局资料]"));
        assertTrue(withStrategy.plannerAnchorPack(state).startsWith("[局面]"),
                "锚点以局面开头（最重要的参考）:\n" + withStrategy.plannerAnchorPack(state));
        assertFalse(withStrategy.plannerAnchorPack(state).contains("[规则速查]"),
                "静态资料不进锚点（独立消息，跨回合一致）:\n" + withStrategy.plannerAnchorPack(state));

        assertFalse(builder.anchorPack(state).contains("[卡组思路]"));
        assertFalse(new ContextPackBuilder(facts, new DuelMathService(), "   ")
                .anchorPack(state).contains("[卡组思路]"), "空白串等同于没填");
    }

    @Test
    void staticMaterialIsByteStableForPrefixCache() {
        // 前缀缓存的前提：整局内静态资料逐字节一致（与回合/局面无关），动态段随局面变化
        ContextPackBuilder withDigest = new ContextPackBuilder(facts, new DuelMathService(),
                "思路：先交闪赚卡", ContextPackBuilder.RULE_DIGEST);
        DuelState early = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        DuelState late = TestDuels.state(4, false, List.of(), List.of(), List.of(), List.of(), 3000, 6000);

        assertEquals(withDigest.judgeStaticMaterial(), withDigest.judgeStaticMaterial());
        assertEquals(withDigest.plannerStaticMaterial(), withDigest.plannerStaticMaterial());
        assertFalse(withDigest.dynamicBoardPack(early).equals(withDigest.dynamicBoardPack(late)),
                "动态段要随局面变（否则局面就废了）");
        assertTrue(withDigest.anchorPack(early)
                        .startsWith(withDigest.judgeStaticMaterial()),
                "anchorPack 兼容入口以静态资料开头");
    }

    @Test
    void ruleDigestIsInjectedBeforeBoardAndOnlyWhenProvided() {
        // 规则速查是通用背景知识：要预置、要出现在局面之前，但不能在没有提供时凭空生成。
        ContextPackBuilder withDigest = new ContextPackBuilder(facts, new DuelMathService(),
                null, "# 速查\n- 连接怪兽只能从额外卡组特召到额外怪兽区。");
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String anchor = withDigest.anchorPack(state);
        assertTrue(anchor.contains("[规则速查]"), "要注入规则速查段:\n" + anchor);
        assertTrue(anchor.contains("连接怪兽只能从额外卡组特召到额外怪兽区"), "正文要带进去:\n" + anchor);
        assertTrue(anchor.indexOf("[规则速查]") < anchor.indexOf("[局面]"), "规则应作为背景先于局面:\n" + anchor);

        assertFalse(builder.anchorPack(state).contains("[规则速查]"), "未提供 digest 时不该出现");
        assertFalse(new ContextPackBuilder(facts, new DuelMathService(), null, "   ")
                .anchorPack(state).contains("[规则速查]"), "空白串等同于没提供");
    }

    @Test
    void ruleDigestSeparatesActivationFromResolution() {
        String digest = ContextPackBuilder.RULE_DIGEST;

        assertTrue(digest.contains("发动 → 结算 → 生效"), "要把三段态写清:\n" + digest);
        assertTrue(digest.contains("从**最后一环往前**"), "逆算推演要写成可执行的动作:\n" + digest);
        assertTrue(digest.contains("以 [局面] 为准"), "反证优先，场面压过预期:\n" + digest);
        assertTrue(digest.contains("发卡者的视角"), "\"对方\"是发卡者视角，不是绝对的我方:\n" + digest);
    }

    @Test
    void endTurnComesWithAPerCategoryAuditOfWhatIsStillAvailable() {
        // 用户的判断标准：结束回合前，摆着的每个可选动作都该被评估过并主动放弃。
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(),
                List.of(),
                List.of(),
                List.of(TestDuels.cardChoice(2, TestDuels.WIDOW_ANCHOR)),
                List.of(),
                true, true, false);
        DecisionPoint point = new DecisionPoint(1, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        String section = builder.candidatesSection(point);

        assertTrue(section.contains("[收尾审计]"), "结束回合要带审计段:\n" + section);
        assertTrue(section.contains("通常召唤 1 项"), section);
        assertTrue(section.contains("盖放魔陷 1 项"), "盖放魔陷这一行必须被点名（实机就是漏了它）:\n" + section);
        assertTrue(section.contains("逐类写一句放弃理由"), "要求逐类给出放弃理由才算评估过:\n" + section);
    }

    @Test
    void endTurnOptionSaysHowManyQuickPlaysAreStillUnset() {
        long quick = 98338152L;
        CardFacts withQuick = new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                if (passcode == quick) {
                    return Optional.of(new CardFact(quick, "闪刀机-黑寡妇抓锚", "",
                            "SPELL", "速攻魔法", "", "", null, null, null, "①：测试用。"));
                }
                return TestDuels.facts().byPasscode(passcode);
            }

            @Override
            public long knownCount() {
                return 1;
            }
        };
        DuelState base = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        CardInstance quickCard = TestDuels.instance(901, quick, ZoneRef.hand(PlayerSide.SELF));
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "me", 8000,
                List.of(quickCard), 30, 10, List.of(), List.of(), List.of());
        DuelState state = new DuelState(base.duelRule(), base.turnNumber(), base.turnPlayer(), base.phase(),
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, base.opponent()),
                base.cards(), base.currentChain(), base.result(), base.selfSeat());

        ContextPackBuilder quickBuilder = new ContextPackBuilder(
                withQuick, new DuelMathService(), null, null);
        String section = quickBuilder.candidatesSection(TestDuels.mainPhasePoint(state, List.of(), List.of()));

        assertTrue(section.contains("结束回合（提醒"), "提醒要挂在结束回合这个选项上:\n" + section);
        assertTrue(section.contains("速攻/陷阱没盖"), section);
        assertTrue(section.contains("盖放不消耗卡"), "要说清盖放不是浪费:\n" + section);
    }

    @Test
    void handSpellsAreCalledOutAsUnusableInsteadOfLeftImplicit() {
        DuelState myTurn = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        String attack = builder.anchorPack(myTurn);
        assertTrue(attack.contains("[本回合可用性]"), "自己回合也要点名手牌魔陷的可用性:\n" + attack);
        assertTrue(attack.contains("盖到魔陷区"), "要给出可执行结论（要留阻抗就盖下去）:\n" + attack);

        DuelState oppTurn = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        String defend = builder.anchorPack(oppTurn);
        assertTrue(defend.contains("发不出来"), "对手回合要点明手牌魔陷不可用:\n" + defend);
        assertFalse(defend.contains("灰流丽\u300d〔"), "陷阱/手坑不要混进这条（点错比不说更糟）:" + defend);
    }

    @Test
    void ruleDigestStatesTheTimingAndEffectFactsModelsGetWrong() {
        String digest = ContextPackBuilder.RULE_DIGEST;

        assertTrue(digest.contains("盖的当回合不能发动") && digest.contains("只适用于速攻魔法和陷阱"),
                "盖放当回合的发动限制只能落在速攻/陷阱上:\n" + digest);
        assertTrue(digest.contains("盖的当回合就能发动"), "通常魔法盖的当回合可以发动:\n" + digest);
        assertTrue(digest.contains("手牌里的速攻魔法、通常魔法、陷阱，在对手回合一律发不出来"),
                "要说清手牌速攻在对手回合不可用:\n" + digest);
        assertTrue(digest.contains("手坑") && digest.contains("已盖在魔陷区的速攻魔法"),
                "对手回合可用的三类牌必须列全（漏了手坑，模型会把灰流丽们当成不能用）:\n" + digest);
        assertTrue(digest.contains("这回合先盖下去"), "要给出可执行结论（要留阻抗就现在盖）:\n" + digest);
        assertTrue(digest.contains("不破坏、仍留在场"), "无效≠破坏，卡还在场:\n" + digest);
        assertTrue(digest.contains("ATK/DEF 不变"), "无效不改攻守，不能用来压打点:\n" + digest);
        assertTrue(digest.contains("取对象") && digest.contains("不受效果影响"),
                "取对象/不取对象的抗性区别要写:\n" + digest);
        assertTrue(digest.length() <= 8_000, "速查本身不该超过注入上限");
    }

    @Test
    void skeletonPlanRendersOnlyTheTacticalIntent() {
        // 计划路径被作废（steps 清空）后，上下文仍要带上"目标/思路"，但不该出现逐步执行标记
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());
        TurnPlan skeleton = new TurnPlan("P2:SELF#1", 2, "先解场再赚卡，别为打点烧资源",
                "保住资源等对手先摊开", List.of(), List.of(), 0, 0.4);

        String pack = builder.decisionPack(point, skeleton);

        assertTrue(pack.contains("步骤已作废"), "要标明路径已作废:\n" + pack);
        assertTrue(pack.contains("保住资源等对手先摊开"), "目标要保留:\n" + pack);
        assertTrue(pack.contains("别为打点烧资源"), "思路要保留:\n" + pack);
        assertFalse(pack.contains("▶"), "作废后不该再有逐步执行标记:\n" + pack);
    }

    private static PlanStep step(
            int index, String verb, long passcode, String why) {
        return new PlanStep(index,
                RequestKind.MAIN_PHASE,
                List.of(ActionSpec.of(verb, passcode)),
                Map.of(), Map.of("why", why), 0.8);
    }

    @Test
    void spellTrapCandidatesCarryTheirCardTypeButMonstersDoNot() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(),
                List.of(TestDuels.cardChoice(2, TestDuels.ENGAGE)),
                List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(1, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "state-hash", "request-hash",
                RequestKind.MAIN_PHASE);

        String section = builder.candidatesSection(point);

        // 只看"候选行"本身（[候选卡面] 里带种类是另一回事，那是给"选了会不会白费"用的）
        String candidateLines = section.contains("[候选卡面]")
                ? section.substring(0, section.indexOf("[候选卡面]")) : section;
        assertTrue(candidateLines.contains("〔通常魔法〕"), "魔陷候选要带卡种:\n" + candidateLines);
        assertFalse(candidateLines.contains("〔效果怪兽〕"),
                "怪兽候选不需要种类标记（攻守已足够）:\n" + candidateLines);
    }

    @Test
    void advisoryPlanReadsAsAdviceNeverAsSteps() {
        // 顾问模式（PLANNER_PATH_PLANNING=false）：计划本来就没有路径，措辞必须说清
        // "这是建议、你自己选"，而不是"步骤已作废"（后者会让模型以为是异常状态）。
        ContextPackBuilder advisory = new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService(), null, null, null, true);
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());
        // thinking 由解析器把 analysis/tactics/combo 三段拼成
        String thinking = "场面：主怪区空，墓地魔法 0，对手后场 1 张。\n"
                + "战术：先检索建立循环，被灰流丽就转盖后场。\n"
                + "combo：交闪找零衣，通召站中间列，战刀机留手上。";
        TurnPlan advisoryPlan = new TurnPlan("P2:SELF#1", 2, thinking,
                "建立交闪循环并保持空场", List.of(), List.of(), 0, 0.7);

        String pack = advisory.decisionPack(point, advisoryPlan);

        assertTrue(pack.contains("顾问模式"), "要标明这是顾问模式:\n" + pack);
        assertFalse(pack.contains("步骤已作废"), "顾问模式不是作废，是本来就不给路径:\n" + pack);
        assertTrue(pack.contains("combo：交闪找零衣"), "combo 叙述不能被截断掉:\n" + pack);
        assertTrue(pack.contains("建立交闪循环并保持空场"), "目标要保留:\n" + pack);
        assertFalse(pack.contains("▶"), "没有路径就不该有逐步标记:\n" + pack);
    }

    @Test
    void plannerAnchorCarriesCardTextSoCombinationsCanBeReasoned() {
        // 只给卡名时，模型只能凭记忆判断"这两张能不能配合"，实战里退化成逐张单卡评价。
        // 规划器锚点必须带效果原文；决策包不带（每次决策都拼，成本太高）。
        List<CardInstance> monsters = List.of(new CardInstance(1, TestDuels.known(TestDuels.KAGARI),
                ZoneRef.mainMonster(PlayerSide.SELF, 0), Position.FACE_UP_ATTACK, Map.of(), List.of()));
        DuelState state = TestDuels.state(2, true, monsters, List.of(),
                List.of(), List.of(), 8000, 8000);

        String planner = builder.plannerAnchorPack(state);
        assertTrue(planner.contains("[卡面]"), "规划器锚点要有卡面段:\n" + planner);
        assertTrue(planner.contains("以自己墓地1张「闪刀」魔法卡为对象"), "要带效果原文:\n" + planner);

        String decision = builder.anchorPack(state);
        assertFalse(decision.contains("[卡面]"), "决策包不带卡面（成本）:\n" + decision);
    }

    @Test
    void plannerAnchorListsTheWholeOwnDeck() {
        // 己方卡组是已知信息：给全了规划器才能判断"这张检索能拿到什么"，不必每次现查。
        // 卡组清单整局不变 → 归入规划器静态消息（跨回合前缀缓存），锚点不重复携带。
        ContextPackBuilder withDeck = new ContextPackBuilder(TestDuels.facts(),
                new DuelMathService(), null, null, null, true,
                List.of(TestDuels.KAGARI, TestDuels.ENGAGE));
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String staticMaterial = withDeck.plannerStaticMaterial();

        assertTrue(staticMaterial.contains("[卡组]"), "要有卡组段:\n" + staticMaterial);
        assertTrue(staticMaterial.contains("闪刀姬-燎里"), "卡组里的卡要列出:\n" + staticMaterial);
        assertTrue(staticMaterial.contains("闪刀起动-交闪"), staticMaterial);
        assertTrue(staticMaterial.contains("〔连接怪兽"),
                "要带种类，模型才能判断能不能特召:\n" + staticMaterial);
        assertFalse(withDeck.plannerAnchorPack(state).contains("[卡组]"),
                "卡组清单在静态消息里，锚点不重复");
    }

    @Test
    void phaseActionsCarryRealCandidateIds() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.HORNET)), List.of());

        var flags = point.candidatesOf(Candidate.FlagC.class);
        assertFalse(flags.isEmpty(), "主阶段的阶段动作要成为候选");
        assertTrue(flags.stream().anyMatch(f -> f.flag() == Candidate.Flag.END_TURN), "要有结束回合");

        String section = builder.candidatesSection(point);
        assertTrue(section.contains("阶段动作："), section);
        assertTrue(section.contains("结束回合"), section);
        // 编号要能对上真实候选（否则模型填了也越界）
        String endTurnId = flags.stream()
                .filter(f -> f.flag() == Candidate.Flag.END_TURN)
                .findFirst().orElseThrow().id().value();
        assertTrue(section.contains(endTurnId + " 结束回合"), section);
        assertTrue(point.candidate(flags.getFirst().id()).isPresent(), "编号必须能回查候选");
    }

    @Test
    void noPseudoCandidateC0SurvivesInAnyRequest() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var main = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.HORNET)), List.of());
        assertFalse(builder.candidatesSection(main).contains("C0"), builder.candidatesSection(main));

        var chain = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(
                        new ChoiceRequest.EffectChoice(
                                TestDuels.cardChoice(202, TestDuels.ENGAGE), 1001), false)));
        var chainPoint = new DecisionPoint(2, 2, state.phase(), PlayerSide.SELF, chain, state,
                Candidates.from(chain), "h", "r", RequestKind.CHAIN);
        String chainSection = builder.candidatesSection(chainPoint);
        assertFalse(chainSection.contains("C0"), chainSection);
        assertTrue(chainSection.contains("弃权项：C"), chainSection);
    }

    @Test
    void chainLinkListsEveryEffectClauseNotJustTheFirstOne() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.SERVANT,
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 0))),
                List.of(), 8000, 8000);
        ChainNode link = new ChainNode(1, TestDuels.known(TestDuels.SERVANT), 1417120049L,
                ZoneRef.mainMonster(PlayerSide.OPPONENT, 0), PlayerSide.OPPONENT);
        state = new DuelState(state.duelRule(), state.turnNumber(), state.turnPlayer(), state.phase(),
                state.players(), state.cards(), List.of(link), state.result(), state.selfSeat());

        ChoiceRequest.ChainRequest request = new ChoiceRequest.ChainRequest(List.of());
        DecisionPoint point = new DecisionPoint(1, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.CHAIN);

        String section = builder.candidatesSection(point);

        assertTrue(section.contains("#1 对方 王之仆人-黑魔术师"), "要标出是谁在发动:\n" + section);
        assertTrue(section.contains("①：这张卡的卡名"), section);
        assertTrue(section.contains("②：这张卡在手卡"), section);
        assertTrue(section.contains("③：自己·对方回合，从手卡丢弃1张魔法卡才能发动。对方场上的魔法·陷阱卡全部破坏。"),
                "③ 是被响应的那条效果，绝不能被截掉:\n" + section);
        assertTrue(section.contains("已发动、尚未结算"), "要说明弃权 = 让它照常结算:\n" + section);
    }

    @Test
    void chainLinkTellsWhichCardIsTheTarget() {
        long targeter = 47222537L;
        CardFacts targetingFacts = new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                if (passcode == targeter) {
                    return Optional.of(new CardFact(targeter, "取对象的测试魔法", "",
                            "SPELL", "通常魔法", "", "", null, null, null,
                            "①：以对方场上1张卡为对象才能发动。那张卡除外。"));
                }
                return TestDuels.facts().byPasscode(passcode);
            }

            @Override
            public long knownCount() {
                return 1;
            }
        };
        DuelState state = TestDuels.state(2, false, List.of(),
                List.of(TestDuels.instance(5, TestDuels.WIDOW_ANCHOR, ZoneRef.spellTrap(PlayerSide.SELF, 2))),
                List.of(), List.of(), 8000, 8000);
        ContextPackBuilder targetingBuilder = new ContextPackBuilder(
                targetingFacts, new DuelMathService(), null, null);

        ChainNode withTarget = new ChainNode(1, TestDuels.known(targeter),
                targeter << 4, ZoneRef.spellTrap(PlayerSide.OPPONENT, 2), PlayerSide.OPPONENT,
                false, List.of(ZoneRef.spellTrap(PlayerSide.SELF, 2)));
        String withTargetSection = targetingBuilder.candidatesSection(
                chainPoint(state, List.of(withTarget)));
        assertTrue(withTargetSection.contains("对象："), "链路要给出对象:\n" + withTargetSection);
        assertTrue(withTargetSection.contains("STZ-3"), "对象要带位置:\n" + withTargetSection);

        ChainNode noTarget = new ChainNode(1, TestDuels.known(targeter),
                targeter << 4, ZoneRef.spellTrap(PlayerSide.OPPONENT, 2), PlayerSide.OPPONENT);
        String noTargetSection = targetingBuilder.candidatesSection(
                chainPoint(state, List.of(noTarget)));
        assertTrue(noTargetSection.contains("协议未给出"), "没有对象时必须明说，不许猜:\n" + noTargetSection);
    }

    private DecisionPoint chainPoint(DuelState state, List<ChainNode> chain) {
        DuelState withChain = new DuelState(state.duelRule(), state.turnNumber(), state.turnPlayer(),
                state.phase(), state.players(), state.cards(), chain, state.result(), state.selfSeat());
        ChoiceRequest.ChainRequest request = new ChoiceRequest.ChainRequest(List.of());
        return new DecisionPoint(1, withChain.turnNumber(), withChain.phase(), PlayerSide.SELF,
                request, withChain, Candidates.from(request), "h", "r", RequestKind.CHAIN);
    }

    @Test
    void chainBlockKeepsEveryLinkMarksNegatedAndShowsResolveOrder() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.SERVANT,
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 0))),
                List.of(), 8000, 8000);
        ChainNode first = new ChainNode(1, TestDuels.known(TestDuels.SERVANT), 1417120049L,
                ZoneRef.mainMonster(PlayerSide.OPPONENT, 0), PlayerSide.OPPONENT);
        ChainNode second = new ChainNode(2, TestDuels.known(TestDuels.RAY), 0L,
                ZoneRef.hand(PlayerSide.SELF), PlayerSide.SELF).asNegated();
        ChainNode third = new ChainNode(3, TestDuels.known(TestDuels.WIDOW_ANCHOR), 0L,
                ZoneRef.spellTrap(PlayerSide.OPPONENT, 2), PlayerSide.OPPONENT);
        state = new DuelState(state.duelRule(), state.turnNumber(), state.turnPlayer(), state.phase(),
                state.players(), state.cards(), List.of(first, second, third), state.result(),
                state.selfSeat());

        DecisionPoint point = new DecisionPoint(1, state.turnNumber(), state.phase(), PlayerSide.SELF,
                new ChoiceRequest.ChainRequest(List.of()), state,
                Candidates.from(new ChoiceRequest.ChainRequest(List.of())), "h", "r", RequestKind.CHAIN);

        String section = builder.candidatesSection(point);

        assertTrue(section.contains("3 环"), "环数要写全:\n" + section);
        assertTrue(section.contains("#1 对方") && section.contains("#2 我方") && section.contains("#3 对方"),
                "三环一个都不能少:\n" + section);
        assertTrue(section.contains("结算次序 #3 → #2 → #1"), "结算次序要直接写出来:\n" + section);
        List<String> linkLines = section.lines()
                .filter(l -> l.trim().startsWith("#"))
                .toList();
        assertEquals(3, linkLines.size(), "三环一个都不能少:\n" + section);
        assertTrue(linkLines.get(1).contains("已被无效"), "无效标记要落在被无效的那一环上:\n" + section);
        assertFalse(linkLines.get(0).contains("已被无效"), "没被无效的环节不该带标记:\n" + section);
        assertFalse(linkLines.get(2).contains("已被无效"), "没被无效的环节不该带标记:\n" + section);
    }

    @Test
    void subordinateSelectionKnowsWhichEffectItServesAndWhichTargetsAreImmune() {
        DuelState state = TestDuels.state(3, true, List.of(), List.of(),
                List.of(new CardInstance(1, TestDuels.known(TestDuels.DRAGOON),
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 4), Position.FACE_UP_ATTACK, Map.of(), List.of())),
                List.of(), 8000, 8000);
        List<ChoiceRequest.CardChoice> choices = List.of(
                new ChoiceRequest.CardChoice(1, TestDuels.known(TestDuels.DRAGOON),
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 4)),
                new ChoiceRequest.CardChoice(2, CardIdentity.UNKNOWN,
                        ZoneRef.spellTrap(PlayerSide.OPPONENT, 0)));
        ChoiceRequest request = new ChoiceRequest.CardSelectRequest(1, 1, false, choices);
        DecisionPoint point = new DecisionPoint(67, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.CARD_SELECT);

        String scope = builder.describeEffect(TestDuels.SERVANT, TestDuels.SERVANT * 16 + 1);
        String pack = builder.decisionPack(point, null, DecisionMemory.of(scope, List.of(), ""));

        assertTrue(pack.contains("[当前效果]"), "要说明这一手在结算哪个效果:\n" + pack);
        assertTrue(pack.contains("王之仆人-黑魔术师"), pack);
        assertTrue(pack.contains("后续步骤"), "要说明这是效果的后续步骤，不是'要不要发动':\n" + pack);
        assertTrue(pack.contains("[本次请求]"), pack);
        assertTrue(pack.contains("必选，没有弃权项"), "必选请求不能留'弃权'的口子:\n" + pack);
        assertTrue(pack.contains("对方主怪兽区 1 张"), "候选分布要讲清从哪选:\n" + pack);
        assertTrue(pack.contains("[候选卡面]"), pack);
        assertTrue(pack.contains("不会被效果破坏"), "免疫条款必须进上下文，否则又会选它:\n" + pack);
    }

    @Test
    void firstTurnStillOffersEndTurnEvenWithoutBattlePhase() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false,   // canEnterBattlePhase：先手第 1 回合不能进战阶
                true,    // canEndTurn
                false);
        DecisionPoint point = new DecisionPoint(14, 1, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        var flags = point.candidatesOf(Candidate.FlagC.class);
        assertTrue(flags.stream().anyMatch(f -> f.flag() == Candidate.Flag.END_TURN),
                "不能进战阶时也必须能结束回合，否则模型无从表达'空过'");
        assertTrue(flags.stream().noneMatch(f -> f.flag() == Candidate.Flag.ENTER_BATTLE),
                "不能进战阶时不应出现进战阶选项");

        String section = builder.candidatesSection(point);
        String endTurnId = flags.stream().filter(f -> f.flag() == Candidate.Flag.END_TURN)
                .findFirst().orElseThrow().id().value();
        assertTrue(section.contains("阶段动作："), section);
        assertTrue(section.contains(endTurnId + " 结束回合"), section);
    }

    @Test
    void effectYesNoNamesTheClauseBeingActivatedNotJustTheCard() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 7000, 8000);
        ChoiceRequest.CardChoice ray = new ChoiceRequest.CardChoice(1, TestDuels.known(TestDuels.RAY),
                ZoneRef.mainMonster(PlayerSide.SELF, 0));
        // 效果码 = 卡号<<4 | 序号：序号 1 = 第 2 个"可发动"条款 = ②
        ChoiceRequest request = new ChoiceRequest.EffectYesNoRequest(ray, TestDuels.RAY * 16 + 1);
        DecisionPoint point = new DecisionPoint(24, 2, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.EFFECT_YES_NO);

        String section = builder.candidatesSection(point);
        String header = section.lines().filter(l -> l.startsWith("是否发动：")).findFirst().orElse("");

        assertTrue(header.contains("这次要发动的是"), "要标明这次发动的是哪一条:" + header);
        assertTrue(header.contains("②：这张卡在墓地存在的状态"), "必须是 ②（墓地起跳），不是 ①:" + header);
        assertFalse(header.contains("①：自己·对方回合，把这张卡解放"),
                "不能把 ① 说成本次要发动的效果（模型会据此判「解放不了」从而不发动）:" + header);
        assertTrue(section.contains("C2 不发动") || section.contains("不发动"), section);
        assertTrue(section.contains("要不要发") && section.contains("能不能发"),
                "要明说这是「要不要发」，不是「能不能发」:\n" + section);
        assertTrue(section.contains("引擎只在"), "要点明请求出现即代表条件成立:\n" + section);
    }

    @Test
    void effectYesNoWithHintIdDoesNotPassOffTheFirstClauseAsTheAnswer() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var choice = new ChoiceRequest.CardChoice(1, TestDuels.known(TestDuels.CYANOS),
                ZoneRef.mainMonster(PlayerSide.SELF, 0));
        for (long desc : new long[]{0L, 221L}) {
            ChoiceRequest request = new ChoiceRequest.EffectYesNoRequest(choice, desc);
            DecisionPoint point = new DecisionPoint(10, 1, state.phase(), PlayerSide.SELF,
                    request, state, Candidates.from(request), "h", "r", RequestKind.EFFECT_YES_NO);

            String section = builder.candidatesSection(point);
            String header = section.lines().filter(l -> l.startsWith("是否发动：")).findFirst().orElse("");

            assertFalse(header.contains("这次要发动的是"),
                    "desc=" + desc + " 解不出序号时不许写'这次要发动的是'（会把第一条说成本次效果）:" + header);
            assertTrue(section.contains("①：从手卡丢弃1张魔法卡"),
                    "要完整列出条款，模型才有东西可排除:" + section);
            assertTrue(section.contains("②：这张卡召唤·特殊召唤的场合"),
                    "②（本次真正的效果）必须可见:" + section);
            assertTrue(section.contains("本回合已经发动过"),
                    "要教模型用'本回合已发动过'排除 ①——否则它又会把 ① 的代价算到别处:" + section);
            assertFalse(section.contains("这次要发动的是：①"), section);
            assertTrue(section.contains("要不要发") && section.contains("能不能发"), section);
        }
    }

    @Test
    void historySectionTellsTheModelWhatItAlreadyDid() {
        // 直答 是独立单轮调用，没有对话历史：不补"我刚做过什么"，它就会推翻自己
        // （上一手判"泡影留给上级怪兽"，下一手在检索环节就交了）。
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(40, 3, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        var memory = DecisionMemory.of(
                "", List.of("通常召唤 闪刀姬-零衣(26077387) → MMZ-1", "发动 闪刀起动-交闪(25048511)"),
                "先建场面，泡影留给对方上级怪兽");
        String pack = builder.decisionPack(point, null, memory);

        assertTrue(pack.contains("[本回合已做]"), pack);
        assertTrue(pack.contains("1. 通常召唤 闪刀姬-零衣"), pack);
        assertTrue(pack.contains("2. 发动 闪刀起动-交闪"), pack);
        assertTrue(pack.contains("上一手的判断：先建场面"), pack);
        assertTrue(pack.contains("为什么改"), "要给出「可以推翻、但必须说明」的约束:" + pack);
        assertTrue(pack.indexOf("[本回合已做]") < pack.indexOf("[候选]"),
                "历史要排在候选之前，模型先看到自己做过什么:" + pack);
    }

    @Test
    void emptyMemoryAddsNoHistorySection() {
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(1, 3, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        String pack = builder.decisionPack(point, null,
                DecisionMemory.NONE);
        assertFalse(pack.contains("[本回合已做]"), "没有记忆就不该多出这一段:" + pack);
    }

    @Test
    void anchorListsBanishedCardsByName() {
        // 除外区是公开信息，只给张数模型推不出"被除外的是哪只"（复活/回收判断要用名字）
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "me", 8000,
                List.of(), 30, 10,
                List.of(TestDuels.known(TestDuels.ENGAGE)),
                List.of(TestDuels.known(TestDuels.KAGARI)), List.of());
        PlayerState opponent = new PlayerState(PlayerSide.OPPONENT, 1, "opp", 8000,
                List.of(), 25, 12, List.of(), List.of(), List.of());
        DuelState state = new DuelState(5, 2, PlayerSide.SELF, Phase.MAIN_PHASE_1,
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, opponent),
                Map.of(), List.of(), null, 0);

        String anchor = builder.anchorPack(state);
        assertTrue(anchor.contains("除外：闪刀姬-燎里"), anchor);
        assertTrue(anchor.contains("墓地：闪刀起动-交闪"), anchor);
    }

    @Test
    void constraintsCarryDeckCountsForBothSides() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        var constraints = new DuelMathService().constraints(state, facts);
        assertEquals(30, constraints.get("selfMainDeckLeft"));
        assertEquals(10, constraints.get("selfExtraDeckLeft"));
        assertEquals(25, constraints.get("opponentMainDeckLeft"));
        assertEquals(12, constraints.get("opponentExtraDeckLeft"));
    }

    @Test
    void monsterLabelCarriesXyzMaterialsAndCounters() {
        CardInstance material = TestDuels.instance(2, TestDuels.HORNET,
                ZoneRef.mainMonster(PlayerSide.SELF, 1));
        CardInstance xyz = new CardInstance(1, TestDuels.known(TestDuels.KAGARI),
                ZoneRef.mainMonster(PlayerSide.SELF, 0), Position.FACE_UP_ATTACK,
                Map.of(7L, 2), List.of(2L));
        DuelState state = TestDuels.state(2, true, List.of(xyz, material),
                List.of(), List.of(), List.of(), 8000, 8000);

        String anchor = builder.anchorPack(state);
        assertTrue(anchor.contains("素材：大黄蜂浮游单元"), anchor);
        assertTrue(anchor.contains("指示物×2"), anchor);
    }

    @Test
    void recordSectionsAppearOnlyWithTimelinePort() {
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(1, 3, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        // T2（已结算）+ T3（本回合）各一条事件：前缀带 T2，决策包时间线带 T3
        ContextPackBuilder withTimeline = new ContextPackBuilder(
                facts, new DuelMathService(), null, null, null, true, List.of(), null,
                new DuelTimeline() {
                    @Override
                    public List<DuelTimeline.Entry> since(long seq) {
                        List<DuelTimeline.Entry> all = List.of(
                                new DuelTimeline.Entry.LpChanged(1, 2, DuelTimeline.Side.OPPONENT, -500, 7500),
                                new DuelTimeline.Entry.LpChanged(2, 3, DuelTimeline.Side.SELF, -100, 7900));
                        return all.stream().filter(e -> e.seq() > seq).toList();
                    }

                    @Override
                    public List<DuelTimeline.Entry> turn(int turnNumber) {
                        return since(0).stream().filter(e -> e.turn() == turnNumber).toList();
                    }

                    @Override
                    public long latestSeq() {
                        return 2;
                    }
                });

        assertTrue(withTimeline.decisionPack(point).contains("[本回合时间线] T3"),
                "有记录端口时决策包要带本回合时间线");
        assertFalse(builder.decisionPack(point).contains("[本回合时间线]"),
                "没有记录端口就不该多出这一段");
        assertTrue(withTimeline.recordOfClosedTurns(3).contains("[决斗记录]"),
                "已结算回合进记录前缀");
        assertTrue(withTimeline.recordOfClosedTurns(3).contains("T2："),
                "已结算回合逐条展开（回合头后缀取决于该组是否有回合开始事件）");
        assertFalse(withTimeline.recordOfClosedTurns(3).contains("T3"),
                "结算中的回合不进前缀（由决策包时间线承载）");
        assertTrue(withTimeline.plannerAnchorPack(state).contains("[决斗记录]"));
        assertFalse(builder.plannerAnchorPack(state).contains("[决斗记录]"));
    }
}