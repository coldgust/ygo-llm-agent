package com.github.coldgust.ygodomain.card;

/**
 * 魔法卡的种类。依据《官方规则书》：
 * <ul>
 *   <li>通常魔法：发动后效果处理完毕即送入墓地</li>
 *   <li>永续魔法：发动后留在场上，效果持续</li>
 *   <li>装备魔法：装备给一只表侧怪兽，随对象离场而破坏</li>
 *   <li>场地魔法：放置于场地魔法区，双方各可维持一张表侧场地魔法</li>
 *   <li>速攻魔法：任意时点（含对方回合，需先盖放）可发动，速度为 2</li>
 *   <li>仪式魔法：与仪式怪兽配套使用</li>
 * </ul>
 */
public enum SpellType {
    NORMAL,
    CONTINUOUS,
    EQUIP,
    FIELD,
    QUICK_PLAY,
    RITUAL
}
