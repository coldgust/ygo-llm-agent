package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorKeeper;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.ChainTargetRef;
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
    private final ContextPackBuilder builder = new ContextPackBuilder(facts);

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
        String anchor = builder.anchorPack(state);
        assertTrue(anchor.contains("魔陷（3）"), "场面行要内联后场张数:\n" + anchor);
        assertFalse(anchor.contains("盖卡、盖卡、盖卡、盖卡"), "锚点里不该出现重复渲染:\n" + anchor);
        assertTrue(anchor.contains("闪刀起动-交闪(25048511)(里侧)"), "我方盖卡应带名字:\n" + anchor);
        assertEquals(3, occurrences(anchor, "(里侧)"), "三张盖卡就渲染三条:\n" + anchor);
    }

    @Test
    void boardSlotsListOccupancyPerCellInPlainChinese() {
        // 位置要成为决策，模型必须知道每格站着什么——只给"怪兽区"三个字它就写不出「我方主怪兽区3」
        List<CardInstance> monsters = List.of(new CardInstance(1, TestDuels.known(TestDuels.HORNET),
                ZoneRef.mainMonster(PlayerSide.SELF, 2), Position.FACE_UP_ATTACK, Map.of(), List.of()));
        List<CardInstance> backrow = List.of(
                backrow(2, TestDuels.known(TestDuels.ENGAGE), 0, Position.FACE_DOWN_DEFENSE));
        DuelState state = TestDuels.state(2, true, monsters, backrow, List.of(), List.of(), 8000, 8000);

        String anchor = builder.anchorPack(state);
        assertTrue(anchor.contains("[场地槽位]"), "锚点要有逐格占用:\n" + anchor);
        assertTrue(anchor.contains("3大黄蜂浮游单元"), "第 3 格应显示卡名:\n" + anchor);
        assertTrue(anchor.contains("主怪兽区: 1空 2空 3"), "空格要标成空，模型才知道能放哪:\n" + anchor);
        assertTrue(anchor.contains("魔陷区: 1闪刀起动-交闪"), "魔陷区也要逐格:\n" + anchor);
        assertTrue(anchor.contains("场地魔法区: 空"), "场地魔法区不能漏:\n" + anchor);
    }

    @Test
    void placePackListsZonesInPlainChinese() {
        // 位置不再单独发一次请求，但包一旦构造出来，候选仍要写清归属与区域名（中文，不带代号）
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.PlaceRequest(1, List.of(
                ZoneRef.mainMonster(PlayerSide.SELF, 0), ZoneRef.mainMonster(PlayerSide.SELF, 1)));
        DecisionPoint point = new DecisionPoint(3, 2, state.phase(), PlayerSide.SELF, request, state,
                Candidates.from(request), "h", "r", RequestKind.PLACE);

        String pack = builder.judgeSnapshot(point, null, "");
        assertTrue(pack.contains("C1 我方主怪兽区1"), "候选要写清归属+区域名:\n" + pack);
        assertFalse(pack.contains("MMZ-"), "渲染层不再出现代号（资料库里没有这种写法）:\n" + pack);
        assertTrue(pack.contains("[场地槽位]"), "位置需要占用信息:\n" + pack);
    }

    @Test
    void anchorNoLongerCarriesLethalVerdict() {
        // 回合开始时"空场 → 不可斩杀"没有信息量，已从锚点摘除；计数内联在战场行
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        String anchor = builder.anchorPack(state);

        assertFalse(anchor.contains("斩杀"), "锚点里不该再有斩杀判定:\n" + anchor);
        assertTrue(anchor.contains("卡组剩余 我 主30/额10，对 主25/额12"),
                "局面行要带双方卡组剩余:\n" + anchor);
        assertTrue(anchor.contains("魔陷区（空）"), "空后场要有明确表述:\n" + anchor);
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
        assertTrue(anchor.contains("魔陷（1）"), "对手场面行要内联后场张数:\n" + anchor);
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
        assertTrue(section.contains("放弃优先权（不发动）"), "必须给出弃权选项:\n" + section);
        assertTrue(section.contains("弃权项：C"), "弃权项要带编号（不再是伪候选 C0）:\n" + section);
        assertTrue(section.contains("选发"), "要说明是选发而非必发:\n" + section);
        assertTrue(section.contains("均已通过规则校验"), "要声明候选已合法，别让模型自己验条件:\n" + section);
    }

    @Test
    void adviceSnapshotCarriesStrategyGoalTacticsAndIntel() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());

        AdvisorKeeper.Snapshot advice = snapshot("A2:SELF#1", 2,
                "对手是闪刀：换资源换赢，不抢血", "先展开再压制", "检索建立循环，抓检索时点",
                "对手已交 1 张阻抗；灰流丽可能还在手");

        String pack = builder.judgeSnapshot(point, advice, "");

        assertTrue(pack.contains("[规划器情报] A2:SELF#1"), "决策器要看到建议编号:\n" + pack);
        assertFalse(pack.contains("仅供参考"), "本回合发布的建议不标 stale:\n" + pack);
        assertTrue(pack.contains("本局策略：对手是闪刀"), "策略要带:\n" + pack);
        assertTrue(pack.contains("本回合目标：先展开再压制"), "目标要带:\n" + pack);
        assertTrue(pack.contains("战术：检索建立循环"), "战术要带:\n" + pack);
        assertTrue(pack.contains("情报要点：对手已交 1 张阻抗"), "情报要带（决策器的时点参考）:\n" + pack);
        assertTrue(pack.contains("偏离建议，因为"), "要写明偏离建议的规则:\n" + pack);

        // 没有建议（规划器未就绪）时，退化为纯局面决策，不能凭空造出情报段
        assertFalse(builder.judgeSnapshot(point, null, "").contains("[规划器情报]"),
                "无建议时不该出现情报段");
    }

    @Test
    void adviceSnapshotMarksStaleAdviceFromLastTurn() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());

        // 上回合发布的建议仍未被替换（本回合锚点失败）：渲染层标"仅供参考"
        AdvisorKeeper.Snapshot stale = snapshot("A1:SELF#1", 1,
                "", "上一回合的目标", "", "");

        String pack = builder.judgeSnapshot(point, stale, "");
        assertTrue(pack.contains("[规划器情报] A1:SELF#1（上回合制定，仅供参考）"),
                "跨回合的旧建议快照要标 stale:\n" + pack);
        assertTrue(pack.contains("本回合目标：上一回合的目标"), "内容仍要渲染（方向参考）:\n" + pack);
    }

    /** 测试用建议快照；空串字段不渲染（adviceSection 只带非空段）。 */
    private static AdvisorKeeper.Snapshot snapshot(String adviceId, int turn,
                                                   String strategy, String goal, String tactics,
                                                   String intel) {
        AdvisorAnswer answer = new AdvisorAnswer(0.8, strategy, "", "", goal,
                List.of(), tactics, intel, "");
        return new AdvisorKeeper.Snapshot(answer, adviceId, turn, 0.8);
    }

    @Test
    void deckStrategyIsInjectedFirstAndActsAsGuidance() {
        // 用户在界面里写的打法：在静态资料段最前面，且带"冲突时以此为准"的措辞，
        // 否则模型会把它当成普通参考资料忽略掉。
        ContextPackBuilder withStrategy = ContextPackBuilder.builder(facts)
                .deckStrategy("闪刀是资源型卡组：先用交闪赚卡，别为了打点把场铺满；\n"
                        + "墓地魔法不足 3 张时不要发抓锚的抢控制权效果。")
                .build();
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var point = TestDuels.mainPhasePoint(state,
                List.of(TestDuels.cardChoice(201, TestDuels.WIDOW_ANCHOR)), List.of());

        String anchor = withStrategy.anchorPack(state);   // 兼容入口 = 静态 + 动态
        assertTrue(anchor.startsWith("[卡组思路]"), "思路要放在最前面:\n" + anchor);
        assertTrue(anchor.contains("别为了打点把场铺满"), "正文要原样带进去:\n" + anchor);
        assertTrue(anchor.contains("以此为准"), "要说清它是优先级更高的依据:\n" + anchor);

        // 决策快照只带动态内容；静态资料在决策器 memory 里（pinned），记录流由 feed 追加
        String pack = withStrategy.judgeSnapshot(point, null, "");
        assertFalse(pack.contains("[卡组思路]"), "决策快照不再携带静态资料:\n" + pack);
        assertTrue(pack.startsWith("[决策快照]"), "快照以标题开头:\n" + pack);
        String combined = withStrategy.judgeStaticMaterial() + pack;
        assertTrue(combined.indexOf("[卡组思路]") < combined.indexOf("[局面]"),
                "模型阅读顺序仍是思路在前、局面在后:\n" + combined);
        // 连招参考与卡组清单是规划器专属；核心资料（含思路）两边都有
        assertTrue(withStrategy.plannerStaticMaterial().contains("[卡组思路]"));
        // 规划器静态消息独立成整局资料（跨回合前缀缓存），锚点只剩易变部分
        assertTrue(withStrategy.plannerStaticMaterial().startsWith("[整局资料]"));
        assertTrue(withStrategy.plannerSnapshot(state).startsWith("[局面]"),
                "幕僚快照以局面开头（最重要的参考）:\n" + withStrategy.plannerSnapshot(state));
        assertFalse(withStrategy.plannerSnapshot(state).contains("[规则速查]"),
                "静态资料不进快照（pinned 前缀，跨回合一致）:\n" + withStrategy.plannerSnapshot(state));

        assertFalse(builder.anchorPack(state).contains("[卡组思路]"));
        assertFalse(ContextPackBuilder.builder(facts)
                .deckStrategy("   ").build().anchorPack(state).contains("[卡组思路]"), "空白串等同于没填");
    }

    @Test
    void staticMaterialIsByteStableForPrefixCache() {
        // 前缀缓存的前提：整局内静态资料逐字节一致（与回合/局面无关），动态段随局面变化
        ContextPackBuilder withDigest = ContextPackBuilder.builder(facts)
                .deckStrategy("思路：先交闪赚卡")
                .ruleDigest(RuleDigest.TEXT)
                .build();
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
        ContextPackBuilder withDigest = ContextPackBuilder.builder(facts)
                .ruleDigest("# 速查\n- 连接怪兽只能从额外卡组特召到额外怪兽区。")
                .build();
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String anchor = withDigest.anchorPack(state);
        assertTrue(anchor.contains("[规则速查]"), "要注入规则速查段:\n" + anchor);
        assertTrue(anchor.contains("连接怪兽只能从额外卡组特召到额外怪兽区"), "正文要带进去:\n" + anchor);
        assertTrue(anchor.indexOf("[规则速查]") < anchor.indexOf("[局面]"), "规则应作为背景先于局面:\n" + anchor);

        assertFalse(builder.anchorPack(state).contains("[规则速查]"), "未提供 digest 时不该出现");
        assertFalse(ContextPackBuilder.builder(facts)
                .ruleDigest("   ").build().anchorPack(state).contains("[规则速查]"), "空白串等同于没提供");
    }

    @Test
    void ruleDigestSeparatesActivationFromResolution() {
        String digest = RuleDigest.TEXT;

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

        ContextPackBuilder quickBuilder = new ContextPackBuilder(withQuick);
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
        String digest = RuleDigest.TEXT;

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
    void plannerAnchorCarriesCardTextSoCombinationsCanBeReasoned() {
        // 只给卡名时，模型只能凭记忆判断"这两张能不能配合"，实战里退化成逐张单卡评价。
        // 规划器锚点必须带效果原文；决策包不带（每次决策都拼，成本太高）。
        List<CardInstance> monsters = List.of(new CardInstance(1, TestDuels.known(TestDuels.KAGARI),
                ZoneRef.mainMonster(PlayerSide.SELF, 0), Position.FACE_UP_ATTACK, Map.of(), List.of()));
        DuelState state = TestDuels.state(2, true, monsters, List.of(),
                List.of(), List.of(), 8000, 8000);

        String planner = builder.plannerSnapshot(state);
        assertTrue(planner.contains("[卡面]"), "幕僚快照要有卡面段:\n" + planner);
        assertTrue(planner.contains("以自己墓地1张「闪刀」魔法卡为对象"), "要带效果原文:\n" + planner);

        String decision = builder.anchorPack(state);
        assertFalse(decision.contains("[卡面]"), "宿主战场报告不带卡面（成本）:\n" + decision);
    }

    @Test
    void plannerAnchorListsTheWholeOwnDeck() {
        // 己方卡组是已知信息：给全了规划器才能判断"这张检索能拿到什么"，不必每次现查。
        // 卡组清单整局不变 → 归入规划器静态消息（跨回合前缀缓存），锚点不重复携带。
        ContextPackBuilder withDeck = ContextPackBuilder.builder(TestDuels.facts())
                .deckPasscodes(List.of(TestDuels.KAGARI, TestDuels.ENGAGE)).build();
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String staticMaterial = withDeck.plannerStaticMaterial();

        assertTrue(staticMaterial.contains("[卡组]"), "要有卡组段:\n" + staticMaterial);
        assertTrue(staticMaterial.contains("闪刀姬-燎里"), "卡组里的卡要列出:\n" + staticMaterial);
        assertTrue(staticMaterial.contains("闪刀起动-交闪"), staticMaterial);
        assertTrue(staticMaterial.contains("〔连接怪兽"),
                "要带种类，模型才能判断能不能特召:\n" + staticMaterial);
        assertFalse(withDeck.plannerSnapshot(state).contains("[卡组]"),
                "卡组清单在静态消息里，快照不重复");
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
        ContextPackBuilder targetingBuilder = new ContextPackBuilder(targetingFacts);

        ChainNode withTarget = new ChainNode(1, TestDuels.known(targeter),
                targeter << 4, ZoneRef.spellTrap(PlayerSide.OPPONENT, 2), PlayerSide.OPPONENT,
                false, List.of(new ChainTargetRef(ZoneRef.spellTrap(PlayerSide.SELF, 2),
                        TestDuels.known(TestDuels.WIDOW_ANCHOR))));
        String withTargetSection = targetingBuilder.candidatesSection(
                chainPoint(state, List.of(withTarget)));
        assertTrue(withTargetSection.contains("对象："), "链路要给出对象:\n" + withTargetSection);
        assertTrue(withTargetSection.contains("我方魔陷区3"), "对象要带位置（中文区域名）:\n" + withTargetSection);

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
        String pack = builder.judgeSnapshot(point, null, scope);

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
    void snapshotLeavesRecordAndMemoryToDuelChatMemory() {
        // 决策器带记忆后（docs/planner-judge-memory-redesign.md §7）："我刚做过什么"与
        // 过程记录由 memory 的追加流承载，快照不再渲染这些段——重复反而会让模型分不清权威。
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(40, 3, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        String pack = builder.judgeSnapshot(point, null, "");

        assertFalse(pack.contains("[本回合已做]"), "已做动作由记忆承载，快照不渲染:\n" + pack);
        assertFalse(pack.contains("[本回合时间线]"), "本回合过程由记忆承载，快照不渲染:\n" + pack);
        assertFalse(pack.contains("[决斗记录]"), "已结算回合由记忆承载，快照不渲染:\n" + pack);
        assertTrue(pack.contains("[局面]"), "快照仍带实时局面:\n" + pack);
        assertTrue(pack.indexOf("[局面]") < pack.indexOf("[候选]"),
                "局面在候选之前（先看场面再选编号）:\n" + pack);
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
    void boardLineCarriesDeckCountsForBothSides() {
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String board = builder.dynamicBoardPack(state);
        assertTrue(board.contains("卡组剩余 我 主30/额10，对 主25/额12"),
                "局面行要带双方卡组剩余:\n" + board);
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
    void plannerSnapshotCarriesBoardAndCardFactsButNotRecords() {
        // 决斗记录由 DuelChatMemory 的追加流承载（DuelRecordFeed 写入），幕僚快照只带局面与卡面；
        // 重复给反而会让模型分不清哪个是权威。
        DuelState state = TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);

        String snapshot = builder.plannerSnapshot(state);
        assertTrue(snapshot.contains("[局面]"), "快照带实时局面:\n" + snapshot);
        assertFalse(snapshot.contains("[决斗记录]"), "记录由记忆承载，快照不重复:\n" + snapshot);
        assertFalse(snapshot.contains("[卡组思路]"), "静态资料在 pinned 前缀里，快照不重复:\n" + snapshot);
    }
}
