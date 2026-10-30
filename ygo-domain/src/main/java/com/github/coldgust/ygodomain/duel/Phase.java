package com.github.coldgust.ygodomain.duel;

/**
 * 回合的阶段（官方术语：Draw Phase / Standby Phase / Main Phase 1 /
 * Battle Phase（Start Step、Battle Step、Damage Step、End Step）/ Main Phase 2 / End Phase）。
 *
 * <p>规则要点：先手玩家的第一个回合不进行抽卡、不进入战斗阶段；
 * Main Phase 2 只能在 Battle Phase 结束后进入（可选）。
 */
public enum Phase {
    DRAW_PHASE,
    STANDBY_PHASE,
    MAIN_PHASE_1,
    /** Battle Phase 的 Start Step（战斗开始步骤）。 */
    BATTLE_START_STEP,
    /** Battle Step（战斗步骤：宣言攻击）。 */
    BATTLE_STEP,
    /** Damage Step（伤害步骤）。 */
    DAMAGE_STEP,
    /** Damage Step 中的伤害计算时点。 */
    DAMAGE_CALCULATION,
    /** Battle Phase 的 End Step（战斗结束步骤）。 */
    BATTLE_END_STEP,
    MAIN_PHASE_2,
    END_PHASE;

    /** 是否处于 Battle Phase 框架内。 */
    public boolean inBattlePhase() {
        return this == BATTLE_START_STEP || this == BATTLE_STEP || this == DAMAGE_STEP
                || this == DAMAGE_CALCULATION || this == BATTLE_END_STEP;
    }

    /** 是否为主要阶段（Main Phase 1 / 2）。 */
    public boolean isMainPhase() {
        return this == MAIN_PHASE_1 || this == MAIN_PHASE_2;
    }
}
