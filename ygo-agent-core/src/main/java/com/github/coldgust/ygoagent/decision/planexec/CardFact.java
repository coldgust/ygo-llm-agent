package com.github.coldgust.ygoagent.decision.planexec;

/**
 * 一张卡的确定性事实（来自 data/card_rag 的本地查表，非 LLM 记忆）。
 *
 * <p>LLM 输出里引用的每个数字/卡名都必须能在这些事实里找到（§3.3 纪律）。
 *
 * @param passcode  卡片密码
 * @param name      中文名
 * @param nameEn    英文名
 * @param kind      MONSTER / SPELL / TRAP（由 card_type 推导）
 * @param cardType  人类可读的种类串（如 "连接／效果／怪兽"）
 * @param race      种族（中文）
 * @param attribute 属性（中文）
 * @param atk       攻击力（null = ?）
 * @param defense   守备力（null = ? / 连接）
 * @param level     等级/阶级
 * @param effect    效果全文（灵摆卡为怪兽效果部分）
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
