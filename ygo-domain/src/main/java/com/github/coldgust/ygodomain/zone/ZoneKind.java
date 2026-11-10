package com.github.coldgust.ygodomain.zone;

/**
 * 场地区域种类（官方术语，规则书"决斗场"一章）。
 *
 * <p>大师规则（Master Rule 5，2020-04 起）场地布局：
 * <ul>
 *   <li>Main Monster Zone：每方 5 格</li>
 *   <li>Extra Monster Zone：全场 2 格（双方各默认使用 1 格）；当前规则下融合/同调/超量也可直接召唤到主怪兽区，
 *       只有连接怪兽与从额外卡组表侧召唤的灵摆怪兽必须使用额外怪兽区或连接标记指向的主怪兽区</li>
 *   <li>Spell &amp; Trap Zone：每方 5 格</li>
 *   <li>Pendulum Zone：即最左与最右的 Spell &amp; Trap Zone（放置灵摆怪兽作为魔法时）</li>
 *   <li>Field Zone：每方 1 格，双方各可维持一张表侧场地魔法</li>
 *   <li>Graveyard / Banished / Deck / Extra Deck / Hand</li>
 * </ul>
 */
public enum ZoneKind {
    DECK,
    EXTRA_DECK,
    HAND,
    MAIN_MONSTER_ZONE,
    EXTRA_MONSTER_ZONE,
    SPELL_TRAP_ZONE,
    PENDULUM_ZONE,
    FIELD_ZONE,
    GRAVEYARD,
    BANISHED;

    /** 该区域中的卡是否有"表示形式"（位置排列有序的场地区域为 true）。 */
    public boolean hasPosition() {
        return switch (this) {
            case MAIN_MONSTER_ZONE, EXTRA_MONSTER_ZONE, SPELL_TRAP_ZONE, PENDULUM_ZONE, FIELD_ZONE -> true;
            default -> false;
        };
    }

    public boolean isMonsterZone() {
        return this == MAIN_MONSTER_ZONE || this == EXTRA_MONSTER_ZONE;
    }

    /** 是否为魔法陷阱类区域（含灵摆、场地）。 */
    public boolean isSpellTrapZone() {
        return this == SPELL_TRAP_ZONE || this == PENDULUM_ZONE || this == FIELD_ZONE;
    }
}
