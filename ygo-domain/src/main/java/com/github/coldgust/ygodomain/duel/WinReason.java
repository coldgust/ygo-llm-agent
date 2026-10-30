package com.github.coldgust.ygodomain.duel;

/**
 * 对局结束原因。规则书层面：LP 归零 / 无法抽卡（Deck Out）/ 卡片效果特殊胜利；
 * 协议层附加了超时与放弃。
 */
public enum WinReason {
    /** 正常胜负：LP 归零或规则书层面的特殊胜利。 */
    NORMAL,
    /** 双方同时归零等平局情形（抽死）。 */
    DRAW,
    /** 回合计时超时（协议注入，非规则书范畴）。 */
    TIMEOUT,
    /** 强制结束（如换备超时、房主中止）。 */
    FORCED
}
