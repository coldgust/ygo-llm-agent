package com.github.coldgust.ygodomain.card;

import java.util.EnumSet;
import java.util.Set;

/**
 * 怪兽的种类与能力标志（可叠加，对应卡片信息栏的类别集合）。
 *
 * <p>依据《官方规则书》：
 * <ul>
 *   <li>主卡组怪兽：通常、效果、仪式、灵摆（灵摆怪兽同时视为魔法卡使用）</li>
 *   <li>额外卡组怪兽：融合、同调、超量、连接、灵摆（从额外卡组表侧特殊召唤的场合）</li>
 *   <li>能力子类（可与上述叠加）：调整、二重、灵魂、同盟、卡通、反转</li>
 * </ul>
 *
 * <p>一个怪兽卡通常持有 1–3 个标志，例如： EFFECT + PENDULUM（效果灵摆怪兽）、
 * EFFECT + TUNER（调整效果怪兽）。
 */
public enum MonsterType {
    /** 通常怪兽（无效果文本，黄色卡框）。 */
    NORMAL,
    /** 效果怪兽（橙色卡框）。 */
    EFFECT,
    /** 仪式怪兽：由仪式魔法及其指定的祭品仪式召唤。 */
    RITUAL,
    /** 融合怪兽：由融合素材与注明"融合召唤"的卡片特殊召唤，放入额外卡组。 */
    FUSION,
    /** 同调怪兽：1 只调整 + 若干非调整，等级之和精确相等，放入额外卡组。 */
    SYNCHRO,
    /** 超量怪兽：由同等级怪兽叠放召唤，以阶级取代等级，放入额外卡组。 */
    XYZ,
    /** 灵摆怪兽：半怪兽半魔法，设置灵摆刻度后可灵摆召唤。 */
    PENDULUM,
    /** 连接怪兽：无等级、无守备力，以连接标记（Link Arrow）取代，放入额外卡组；不能转为守备表示。 */
    LINK,
    /** 调整怪兽：同调召唤的素材要件。 */
    TUNER,
    /** 二重怪兽：再度通常召唤后获得效果。 */
    GEMINI,
    /** 灵魂怪兽：召唤/反转的回合结束阶段回到手牌。 */
    SPIRIT,
    /** 同盟怪兽：可装备为装备卡，解除装备后回到怪兽状态。 */
    UNION,
    /** 卡通怪兽。 */
    TOON,
    /** 反转怪兽：反转时发动效果。 */
    FLIP;

    /** 额外卡组怪兽类别（融合/同调/超量/连接；灵摆以表侧形式入额外卡组）。 */
    public static final Set<MonsterType> EXTRA_DECK_KINDS = EnumSet.of(FUSION, SYNCHRO, XYZ, LINK);

    /** 是否属于额外卡组怪兽。 */
    public boolean isExtraDeckKind() {
        return this == FUSION || this == SYNCHRO || this == XYZ || this == LINK;
    }

    /** 是否为灵摆怪兽（灵摆召唤规则适用）。 */
    public boolean isPendulumKind() {
        return this == PENDULUM;
    }
}
