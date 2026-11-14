package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置决策的"人机共同语"：模型写的 zone 必须能被确定性解析回具体格子，
 * 否则计划里的位置就只是文字，落不了地。
 */
class ZoneCodesTest {

    @Test
    void codesAreOneBasedAndRoundTrip() {
        ZoneRef zone = ZoneRef.mainMonster(PlayerSide.SELF, 2);
        assertEquals("MMZ-3", ZoneCodes.code(zone), "旧代号只留作解析兼容，不再渲染给模型");
        assertEquals(zone, ZoneCodes.parse("MMZ-3", PlayerSide.SELF).orElseThrow());
        assertEquals(zone, ZoneCodes.parse("mmz3", PlayerSide.SELF).orElseThrow());
        assertEquals(zone, ZoneCodes.parse("M3", PlayerSide.SELF).orElseThrow());
        assertEquals(zone, ZoneCodes.parse("我方主怪兽区#3", PlayerSide.SELF).orElseThrow());
        assertEquals(zone, ZoneCodes.parse("主怪兽区3", PlayerSide.SELF).orElseThrow());
        assertEquals(zone, ZoneCodes.parse("我方主怪兽区3", PlayerSide.SELF).orElseThrow(),
                "渲染出来的中文写法必须能原样读回");
    }

    @Test
    void extraMonsterZoneIsNotSwallowedByMainMonsterZone() {
        ZoneRef emz = new ZoneRef(ZoneKind.EXTRA_MONSTER_ZONE, PlayerSide.SELF, 0);
        assertEquals(emz, ZoneCodes.parse("EMZ-1", PlayerSide.SELF).orElseThrow());
        assertEquals(emz, ZoneCodes.parse("额外怪兽区1", PlayerSide.SELF).orElseThrow(),
                "中文'额外怪兽区'里也含'怪兽区'，不能被主怪兽区分支抢走");
        assertEquals(emz, ZoneCodes.parse("我方额外怪兽区1", PlayerSide.SELF).orElseThrow());
    }

    @Test
    void spellTrapAndFieldZonesParse() {
        ZoneRef stz = ZoneRef.spellTrap(PlayerSide.SELF, 4);
        assertEquals("STZ-5", ZoneCodes.code(stz));
        assertEquals(stz, ZoneCodes.parse("STZ-5", PlayerSide.SELF).orElseThrow());
        assertEquals(stz, ZoneCodes.parse("魔陷区5", PlayerSide.SELF).orElseThrow());
        assertEquals(stz, ZoneCodes.parse("我方魔陷区5", PlayerSide.SELF).orElseThrow());

        ZoneRef field = ZoneRef.field(PlayerSide.SELF);
        assertEquals("FZ", ZoneCodes.code(field));
        assertEquals(field, ZoneCodes.parse("FZ", PlayerSide.SELF).orElseThrow());
        assertEquals(field, ZoneCodes.parse("场地魔法区", PlayerSide.SELF).orElseThrow());
        assertEquals(field, ZoneCodes.parse("我方场地魔法区", PlayerSide.SELF).orElseThrow());
    }

    @Test
    void ownerPrefixWins() {
        Optional<ZoneRef> opp = ZoneCodes.parse("对方主怪兽区#2", PlayerSide.SELF);
        assertEquals(PlayerSide.OPPONENT, opp.orElseThrow().owner());
        assertEquals(PlayerSide.OPPONENT,
                ZoneCodes.parse("对方主怪兽区2", PlayerSide.SELF).orElseThrow().owner());
    }

    @Test
    void outOfRangeSequenceIsClampedInsteadOfCrashing() {
        assertEquals(4, ZoneCodes.parse("MMZ-9", PlayerSide.SELF).orElseThrow().sequence());
        assertEquals(0, ZoneCodes.parse("EMZ-0", PlayerSide.SELF).orElseThrow().sequence());
    }

    @Test
    void garbageIsRejected() {
        assertTrue(ZoneCodes.parse("中间那格", PlayerSide.SELF).isEmpty());
        assertTrue(ZoneCodes.parse("手牌", PlayerSide.SELF).isEmpty());
        assertTrue(ZoneCodes.parse(null, PlayerSide.SELF).isEmpty());
    }

    @Test
    void labelAndDescribeArePlainChinese() {
        ZoneRef zone = ZoneRef.mainMonster(PlayerSide.SELF, 2);
        assertEquals("我方主怪兽区3", ZoneCodes.label(zone));
        assertEquals("我方主怪兽区3", ZoneCodes.describe(zone),
                "渲染层不再输出代号：资料库里全是中文区域名，代号模型对不上");
        assertFalse(ZoneCodes.describe(zone).contains("MMZ"), ZoneCodes.describe(zone));
        assertTrue(ZoneCodes.isBoardSlot(zone));
        assertFalse(ZoneCodes.isBoardSlot(ZoneRef.hand(PlayerSide.SELF)));
    }
}
