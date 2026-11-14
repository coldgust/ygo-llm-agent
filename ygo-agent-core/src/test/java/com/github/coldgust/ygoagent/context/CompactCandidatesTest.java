package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.model.Candidates;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策请求行里那一行候选清单（2026-11-14 实测反馈）：完整候选只在一次性快照里，
 * 快照用完即弃 → 记录里既看不到当时能选什么，也看不懂历史回复里的裸编号。
 * 这里锁两件事：编号与动词对得上、卡名压到后缀；顺带锁身份行不再给魔法卡印 ATK0。
 */
class CompactCandidatesTest {

    private final CardFacts facts = TestDuels.facts();
    private final ContextPackBuilder builder = new ContextPackBuilder(facts);

    @Test
    void compactListCarriesIdVerbAndShortName() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(),
                List.of(TestDuels.cardChoice(2, TestDuels.WIDOW_ANCHOR)),
                List.of(new ChoiceRequest.EffectChoice(
                        TestDuels.cardChoice(3, TestDuels.ENGAGE), TestDuels.ENGAGE * 16)),
                true, true, true);
        DecisionPoint point = new DecisionPoint(14, 1, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        String line = builder.compactCandidates(point);

        assertTrue(line.startsWith("C1 召唤 大黄蜂浮游单元"),
                "每组要带上「能做什么」的动词: " + line);
        assertTrue(line.contains("C2 盖 黑寡妇抓锚"),
                "盖放要标出来，卡名留后缀（去掉系列前缀）: " + line);
        assertTrue(line.contains("C3 发动 交闪"),
                "效果候选写「发动」，不是裸卡名: " + line);
        assertTrue(line.contains("结束回合") && line.contains("洗手牌"),
                "阶段动作也要在清单里: " + line);
        assertFalse(line.contains("ATK"), "清单只要编号/动词/短名，不搬卡面: " + line);
    }

    @Test
    void chainOptionsAndDeclineKeepTheirIds() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var engage = TestDuels.effectChoice(20, TestDuels.ENGAGE, TestDuels.ENGAGE * 16);
        ChoiceRequest request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(engage, false)));
        DecisionPoint point = new DecisionPoint(3, 2, state.phase(), PlayerSide.OPPONENT,
                request, state, Candidates.from(request), "h", "r", RequestKind.CHAIN);

        String line = builder.compactCandidates(point);

        assertTrue(line.contains("C1 发动 交闪"), line);
        assertTrue(line.contains("C2 放弃优先权（不发动）"),
                "弃权项必须有编号，否则记忆里的 choice 无处可指: " + line);
    }

    @Test
    void chainStackIsWrittenIntoTheRequestLine() {
        // 实测反馈：请求行只说"连锁响应"，记忆里看不出当时在响应谁的什么效果
        ChainNode link = new ChainNode(1, TestDuels.known(TestDuels.SERVANT),
                TestDuels.SERVANT * 16 + 1, ZoneRef.mainMonster(PlayerSide.OPPONENT, 0),
                PlayerSide.OPPONENT);
        DuelState base = TestDuels.state(2, false, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.SERVANT,
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 0))), List.of(), 8000, 8000);
        DuelState state = new DuelState(base.duelRule(), base.turnNumber(), base.turnPlayer(), base.phase(),
                base.players(), base.cards(), List.of(link), base.result(), base.selfSeat());
        var engage = TestDuels.effectChoice(20, TestDuels.ENGAGE, TestDuels.ENGAGE * 16);
        ChoiceRequest request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(engage, false)));
        DecisionPoint point = new DecisionPoint(25, 2, state.phase(), PlayerSide.OPPONENT,
                request, state, Candidates.from(request), "h", "r", RequestKind.CHAIN);

        String chain = builder.compactChain(point);

        assertTrue(chain.startsWith("连锁：连锁1 对方"), "按玩家念连锁的方式编号: " + chain);
        assertTrue(chain.contains("「王之仆人-黑魔术师」"), "要点名是谁的哪张卡: " + chain);
        assertTrue(chain.contains("（对方主怪兽区1）"), "要给出从哪个区域发动: " + chain);
        assertTrue(chain.contains("③"), "要说明这次发动的是哪一条: " + chain);
        assertTrue(chain.contains("对方场上的魔法·陷阱卡全部破坏"), "要带条款正文: " + chain);
    }

    @Test
    void mainPhaseRequestHasNoChainLine() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(14, 1, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        assertEquals("", builder.compactChain(point), "主阶段请求没有连锁上下文，不占一行");
    }

    @Test
    void emptyChainWindowReadsAsActivatableEffectsNotAsAnEmptyChain() {
        // 实机反馈：游戏王里没有"空连锁"这种说法。链上没东西 = 这个时点可以发动效果，
        // 所以抬头写阶段与"此时可发动效果"，不再冒出"连锁：空（…）"那一行
        DuelState state = TestDuels.state(2, false, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        var anchor = TestDuels.fieldEffectChoice(20, TestDuels.WIDOW_ANCHOR, TestDuels.WIDOW_ANCHOR * 16);
        ChoiceRequest request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(anchor, false)));
        DecisionPoint point = new DecisionPoint(3, 2, state.phase(), PlayerSide.OPPONENT,
                request, state, Candidates.from(request), "h", "r", RequestKind.CHAIN);

        assertEquals("第 2 回合 对方回合，主要阶段1，此时可发动效果。", builder.requestHeadline(point));
        assertEquals("", builder.compactChain(point), "链上没东西就不是连锁，不占一行");
        assertFalse(builder.requestHeadline(point).contains("空"),
                "不写\"空连锁/连锁为空\"" + builder.requestHeadline(point));
    }

    @Test
    void headlineNamesThePhaseAndKindWithoutRepeatingThem() {
        DuelState state = TestDuels.state(1, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(TestDuels.cardChoice(1, TestDuels.HORNET)),
                List.of(), List.of(), List.of(), List.of(), List.of(), true, true, false);
        DecisionPoint point = new DecisionPoint(14, 1, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        assertEquals("第 1 回合 我方回合，主要阶段1。", builder.requestHeadline(point),
                "阶段名已经说明是主要阶段，不再写一遍\"主要阶段\"");
    }

    @Test
    void realChainStillSaysChainResponseAndListsEveryLink() {
        DuelState state = TestDuels.state(2, false, List.of(), List.of(),
                List.of(TestDuels.instance(1, TestDuels.SERVANT,
                        ZoneRef.mainMonster(PlayerSide.OPPONENT, 0))), List.of(), 8000, 8000);
        var link = new ChainNode(1, CardIdentity.Known.of(TestDuels.SERVANT),
                TestDuels.SERVANT * 16 + 1, ZoneRef.mainMonster(PlayerSide.OPPONENT, 0),
                PlayerSide.OPPONENT);
        state = new DuelState(state.duelRule(), state.turnNumber(), state.turnPlayer(), state.phase(),
                state.players(), state.cards(), List.of(link), state.result(), state.selfSeat());
        var engage = TestDuels.effectChoice(20, TestDuels.ENGAGE, TestDuels.ENGAGE * 16);
        ChoiceRequest request = new ChoiceRequest.ChainRequest(
                List.of(new ChoiceRequest.ChainRequest.ChainOption(engage, false)));
        DecisionPoint point = new DecisionPoint(25, 2, state.phase(), PlayerSide.OPPONENT,
                request, state, Candidates.from(request), "h", "r", RequestKind.CHAIN);

        assertEquals("第 2 回合 对方回合，主要阶段1，连锁响应。", builder.requestHeadline(point));
        assertTrue(builder.compactChain(point).startsWith("连锁：连锁1 对方"),
                builder.compactChain(point));
    }

    @Test
    void effectCandidatesCarryTheClauseMarkAndTheText() {
        // 实测反馈："C1 发动 零衣" 看不出是 ① 还是 ②，也看不出这条会做什么
        DuelState state = TestDuels.state(2, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ChoiceRequest.EffectChoice(
                        TestDuels.cardChoice(1, TestDuels.RAY), TestDuels.RAY * 16)),
                true, true, false);
        DecisionPoint point = new DecisionPoint(5, 2, state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "h", "r", RequestKind.MAIN_PHASE);

        String list = builder.compactCandidates(point);
        String digest = builder.compactEffectDigest(point);

        assertTrue(list.contains("C1 发动 零衣①"), "同一张多效果卡要能区分是哪一条: " + list);
        assertTrue(digest.contains("零衣①：自己·对方回合，把这张卡解放才能发动"),
                "每个候选要带条款正文，否则判断不了发动的是哪个效果: " + digest);
    }

    @Test
    void spellIdentityLineNeverPrintsAttackStats() {
        // 真实卡片库里魔法/陷阱的 atk 是 0（不是空）→ 旧渲染会印出「〔通常魔法｜ATK0〕」
        CardFacts withZeroAtk = new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                return Optional.of(new CardFact(passcode, "闪刀起动-交闪", "", "SPELL", "通常魔法",
                        "", "", 0, null, null, "①：检索「闪刀」卡。"));
            }

            @Override
            public long knownCount() {
                return 1;
            }
        };
        String line = new CardText(withZeroAtk).card(TestDuels.ENGAGE);

        assertTrue(line.contains("〔通常魔法〕"), line);
        assertFalse(line.contains("ATK"), "非怪兽不给攻守: " + line);
    }

    @Test
    void linkMonsterIdentityLineOmitsDefense() {
        String line = new CardText(facts).card(TestDuels.KAGARI);

        assertTrue(line.contains("ATK1500"), line);
        assertFalse(line.contains("DEF"), "连接怪兽没有守备力，不写 DEF: " + line);
    }

    @Test
    void shortNameStripsOnlyTheSeriesPrefix() {
        assertEquals("黑寡妇抓锚", CandidateSections.shortNameOf("闪刀机-黑寡妇抓锚"));
        assertEquals("雷击", CandidateSections.shortNameOf("雷击"));
    }

    @Test
    void fieldSlotLineNeverPrintsDefenseForLinkMonsters() {
        DuelState state = TestDuels.state(2, true,
                List.of(TestDuels.instance(5, TestDuels.KAGARI,
                        ZoneRef.extraMonster(PlayerSide.SELF), Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000);

        String pack = builder.dynamicBoardPack(state);

        assertTrue(pack.contains("ATK1500"), pack);
        assertFalse(pack.contains("DEF?"),
                "连接怪兽的守备力不是未知，是不存在: " + pack);
    }
}
