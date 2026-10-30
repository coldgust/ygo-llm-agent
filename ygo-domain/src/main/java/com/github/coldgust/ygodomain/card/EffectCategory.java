package com.github.coldgust.ygodomain.card;

/**
 * 效果的分类（按规则书第 4 章"效果的种类"，官方术语）。
 * 注意：该分类来源于卡片文本的规则解释，卡牌数据库通常不直接提供，
 * 由上层（知识模块 / LLM）标注后挂到 {@link CardDefinition} 上。
 */
public enum EffectCategory {
    /** Ignition Effect：主要阶段由玩家主动发动的效果。 */
    IGNITION,
    /** Trigger Effect：满足特定时点自动诱发（可选发动）的效果。 */
    TRIGGER,
    /** Quick Effect：速攻性质，可在对方回合或形成连锁发动的效果。 */
    QUICK,
    /** Continuous Effect：不构成连锁、只要卡片在场上持续有效果。 */
    CONTINUOUS,
    /** Flip Effect：反转时发动的效果（反转效果）。 */
    FLIP
}
