package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数学工具：句柄解析、快照绑定、双方视角、fail-closed 与概率/素材计算。 */
class MathToolTest {

    /** 带名字索引的卡库（生产是 JsonlCardFacts；内存版默认不实现 searchByName）。 */
    private static CardFacts searchableFacts() {
        Map<Long, CardFact> map = new HashMap<>();
        for (long p : List.of(TestDuels.ENGAGE, TestDuels.HORNET, TestDuels.WIDOW_ANCHOR,
                TestDuels.KAGARI, 44405066L, 70902743L)) {
            map.put(p, TestDuels.fact(p));
        }
        return new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                return Optional.ofNullable(map.get(passcode));
            }

            @Override
            public long knownCount() {
                return map.size();
            }

            @Override
            public List<CardFact> searchByName(String keyword, int limit) {
                String query = keyword.toLowerCase(Locale.ROOT).trim();
                return map.values().stream()
                        .filter(fact -> fact.name().toLowerCase(Locale.ROOT).contains(query)
                                || fact.nameEn().toLowerCase(Locale.ROOT).contains(query))
                        .limit(limit)
                        .toList();
            }
        };
    }

    private final CardFacts facts = searchableFacts();
    /** 卡组清单：交闪 ×3（清单口径），其余为凑数的额外卡怪兽。 */
    private final MathTool math = new MathTool(facts,
            List.of(TestDuels.ENGAGE, TestDuels.ENGAGE, TestDuels.ENGAGE, TestDuels.KAGARI), null);

    private static CardInstance monster(long id, long passcode, PlayerSide side, int seq, Position pos) {
        return new CardInstance(id, TestDuels.known(passcode), ZoneRef.mainMonster(side, seq),
                pos, Map.of(), List.of());
    }

    private static CardInstance faceDownOppMonster(int seq) {
        return new CardInstance(90 + seq, CardIdentity.UNKNOWN,
                ZoneRef.mainMonster(PlayerSide.OPPONENT, seq), Position.FACE_DOWN_DEFENSE,
                Map.of(), List.of());
    }

    // ---------------------------------------------------------------- 快照

    @Test
    void failsClosedWithoutSnapshot() {
        String out = math.battleTable(null);
        assertTrue(out.contains("暂无局面快照"), out);
    }

    @Test
    void outputEchoesSnapshotTurnAndPhase() {
        math.bind(TestDuels.state(3, false,
                List.of(monster(1, TestDuels.KAGARI, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000));
        String out = math.battleTable(null);
        assertTrue(out.contains("[局面快照 第3回合 对方回合"), out);
    }

    // ---------------------------------------------------------------- battle_calc

    @Test
    void battleCalcResolvesZoneAndNameHandles() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, TestDuels.HORNET, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        math.bind(state);
        String byZone = math.battleCalc(null, "MMZ-1", "MMZ-1");
        assertTrue(byZone.contains("破坏并造成 1000 伤害"), byZone);
        String byName = math.battleCalc("self", "圣夜降临的骑士", "大黄蜂");
        assertTrue(byName.contains("破坏并造成 1000 伤害"), byName);
        String byPartialName = math.battleCalc("self", "骑士", "蜂");
        assertTrue(byPartialName.contains("破坏并造成 1000 伤害"), byPartialName);
    }

    @Test
    void battleCalcFaceDownTargetIsUnresolved() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(faceDownOppMonster(0)), List.of(), 8000, 8000);
        math.bind(state);
        String out = math.battleCalc("self", "MMZ-1", "MMZ-1");
        assertTrue(out.contains("结算未知"), out);
    }

    @Test
    void battleCalcDirectAttackRejectedWhileMonstersOnField() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, TestDuels.HORNET, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        math.bind(state);
        String out = math.battleCalc("self", "MMZ-1", null);
        assertTrue(out.contains("不能直接攻击"), out);
    }

    @Test
    void battleCalcDirectAttackWhenFieldEmpty() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000);
        math.bind(state);
        String out = math.battleCalc("self", "MMZ-1", null);
        assertTrue(out.contains("直接攻击造成 2500 伤害"), out);
    }

    @Test
    void battleCalcAmbiguousNameListsHandles() {
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(
                        monster(2, TestDuels.HORNET, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK),
                        monster(3, TestDuels.HORNET, PlayerSide.OPPONENT, 1, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        math.bind(state);
        String out = math.battleCalc("self", "MMZ-1", "大黄蜂");
        assertTrue(out.contains("匹配到 2 张"), out);
        assertTrue(out.contains("MMZ-1") && out.contains("MMZ-2"), out);
    }

    // ---------------------------------------------------------------- battle_table

    @Test
    void battleTableFlipsToOpponentThreatView() {
        // 对方大黄蜂(1500) 撞 我方骑士(2500)：攻击方（对方）被破坏、对方受 500 —— 威胁表如实翻面
        DuelState state = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(2, TestDuels.HORNET, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        math.bind(state);
        String threat = math.battleTable("opponent");
        assertTrue(threat.contains("攻击方=对方"), threat);
        // 大黄蜂(1500) 撞 骑士(2500)：差值 1000 全记在攻击方头上
        assertTrue(threat.contains("攻击方受 1000"), threat);
        String own = math.battleTable("self");
        assertTrue(own.contains("攻击方=我方"), own);
    }

    // ---------------------------------------------------------------- damage_projection

    @Test
    void damageProjectionSumsAndFlagsLethal() {
        DuelState state = TestDuels.state(3, true,
                List.of(
                        monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK),
                        monster(2, TestDuels.HORNET, PlayerSide.SELF, 1, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 2000);
        math.bind(state);
        String out = math.damageProjection("self", "");
        assertTrue(out.contains("确定伤害合计 4000"), out);
        assertTrue(out.contains("可斩杀防守方"), out);
    }

    @Test
    void damageProjectionRemovesDestroyedTargets() {
        // 骑士(2500) 先打掉 大黄蜂(1500) 得 1000；第二只大黄蜂(1500) 对残场只能换（0 伤）
        DuelState state = TestDuels.state(3, true,
                List.of(
                        monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK),
                        monster(2, TestDuels.HORNET, PlayerSide.SELF, 1, Position.FACE_UP_ATTACK)),
                List.of(),
                List.of(monster(3, TestDuels.HORNET, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK)),
                List.of(), 8000, 8000);
        math.bind(state);
        String out = math.damageProjection("self", "骑士,大黄蜂");
        assertTrue(out.contains("确定伤害合计 1000"), out);
        assertFalse(out.contains("≥ LP"), out);
    }

    @Test
    void damageProjectionThreatSideComparesSelfLp() {
        DuelState state = TestDuels.state(3, false,
                List.of(),
                List.of(),
                List.of(
                        monster(2, 44405066L, PlayerSide.OPPONENT, 0, Position.FACE_UP_ATTACK),
                        monster(3, TestDuels.HORNET, PlayerSide.OPPONENT, 1, Position.FACE_UP_ATTACK)),
                List.of(), 2000, 8000);
        math.bind(state);
        String out = math.damageProjection("opponent", "");
        assertTrue(out.contains("确定伤害合计 4000"), out);
        assertTrue(out.contains("可斩杀防守方"), out);
    }

    // ---------------------------------------------------------------- draw_odds

    @Test
    void drawOddsComputesHypergeometricFromDeckList() {
        // 清单 3 张，公开区（手牌）已见 1 张 → 卡组内剩 2；主卡组 30 张抽 1：P = 2/30 ≈ 6.7%
        math.bind(TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000));
        String out = math.drawOdds("交闪", 1);
        assertTrue(out.contains("清单 3 张，公开区已见 1 张，主卡组内剩余 2 张"), out);
        assertTrue(out.contains("P(≥1)=6.7%"), out);
        String two = math.drawOdds("交闪", 2);
        assertTrue(two.contains("P(≥2)=0.2%"), two);
    }

    @Test
    void drawOddsRejectsExtraDeckAndUnknownCards() {
        math.bind(TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000));
        String extra = math.drawOdds("燎里", 1);
        assertTrue(extra.contains("额外卡组怪兽"), extra);
        String absent = math.drawOdds("红莲魔龙", 1);
        assertTrue(absent.contains("卡组清单里没有"), absent);
        String unknown = math.drawOdds("根本不存在的卡", 1);
        assertTrue(unknown.contains("无法解析卡"), unknown);
    }

    // ---------------------------------------------------------------- material_math

    @Test
    void materialMathChecksTributes() {
        DuelState enough = TestDuels.state(3, true,
                List.of(
                        monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK),
                        monster(2, TestDuels.HORNET, PlayerSide.SELF, 1, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000);
        math.bind(enough);
        assertTrue(math.materialMath("tribute", 7, null).contains("够"),
                math.materialMath("tribute", 7, null));
        DuelState shortOf = TestDuels.state(3, true,
                List.of(monster(1, 44405066L, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000);
        math.bind(shortOf);
        String out = math.materialMath("tribute", 7, null);
        assertTrue(out.contains("不够"), out);
        assertTrue(out.contains("需祭品 2"), out);
    }

    @Test
    void materialMathChecksXyzAndSynchroLevels() {
        DuelState state = TestDuels.state(3, true,
                List.of(
                        monster(1, TestDuels.HORNET, PlayerSide.SELF, 0, Position.FACE_UP_ATTACK),
                        monster(2, TestDuels.HORNET, PlayerSide.SELF, 1, Position.FACE_UP_ATTACK)),
                List.of(), List.of(), List.of(), 8000, 8000);
        math.bind(state);
        String xyz = math.materialMath("xyz", 4, "MMZ-1,MMZ-2");
        assertTrue(xyz.contains("相符 2/2"), xyz);
        String synchro = math.materialMath("synchro", 8, "MMZ-1,MMZ-2");
        assertTrue(synchro.contains("等级合计 8"), synchro);
        assertTrue(synchro.contains("相等"), synchro);
        assertTrue(synchro.contains("不可核验"), synchro);
        String mismatch = math.materialMath("synchro", 7, "MMZ-1,MMZ-2");
        assertTrue(mismatch.contains("不相等"), mismatch);
    }

    @Test
    void materialMathRejectsUnknownGoal() {
        math.bind(TestDuels.state(3, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000));
        String out = math.materialMath("foo", null, null);
        assertTrue(out.contains("goal 只接受"), out);
    }
}
