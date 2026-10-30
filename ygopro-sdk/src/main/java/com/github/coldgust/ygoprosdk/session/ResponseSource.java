package com.github.coldgust.ygoprosdk.session;

/**
 * 应答来源（对局记录用）。
 */
public enum ResponseSource {
    /** 决策者主动提交。 */
    PLAYER,
    /** MSG_RETRY 后的保底应答。 */
    FALLBACK,
    /** 决策超时后的兜底应答。 */
    TIMEOUT_FALLBACK
}
