package com.github.coldgust.ygoagent.decision.planexec;

/**
 * 一张卡的确定性事实（来自 data/card_rag 的本地查表，非 LLM 记忆）。
 *
 * <p>LLM 输出里引用的每个数字/卡名都必须能在这些事实里找到（§3.3 纪律）。
 */
public record CardFact(
        long passcode,
        String name,
        String nameEn,
        String kind,
        String cardType,
        String race,
        String attribute,
        Integer atk,
        Integer defense,
        Integer level,
        String effect) {

    public boolean isMonster() {
        return kind != null && kind.equals("MONSTER");
    }

    public String label() {
        return name + "(" + passcode + ")";
    }
}
