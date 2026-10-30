package com.github.coldgust.ygodomain.card;

/**
 * 陷阱卡的种类。依据《官方规则书》：陷阱卡必须先盖放，原则上不能在盖放的当回合发动。
 * <ul>
 *   <li>通常陷阱：发动后效果处理完毕即送入墓地</li>
 *   <li>永续陷阱：发动后留在场上</li>
 *   <li>反击陷阱：速度 3，最快，可连锁任何卡片（含其他反击陷阱），通常用于无效</li>
 * </ul>
 */
public enum TrapType {
    NORMAL,
    CONTINUOUS,
    COUNTER
}
