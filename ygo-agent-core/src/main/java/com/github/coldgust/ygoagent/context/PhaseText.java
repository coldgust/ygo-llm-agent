package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.duel.Phase;

/**
 * 阶段名的中文说法：记录行的〔进入X阶段〕、请求行的"当前时点"都走这一处，
 * 免得两处各写一份映射、出现"主要阶段1 / M1"两种叫法。
 */
public final class PhaseText {

    private PhaseText() {
    }

    public static String label(Phase phase) {
        return phase == null ? "未知阶段" : labelOf(phase.name());
    }

    /** 协议里的阶段枚举名（{@code MAIN_PHASE_1} 等）→ 中文；认不出原样返回。 */
    public static String labelOf(String phase) {
        if (phase == null || phase.isBlank()) {
            return "未知阶段";
        }
        return switch (phase) {
            case "DRAW_PHASE" -> "抽卡阶段";
            case "STANDBY_PHASE" -> "准备阶段";
            case "MAIN_PHASE_1" -> "主要阶段1";
            case "BATTLE_START_STEP", "BATTLE_STEP" -> "战斗阶段";
            case "DAMAGE_STEP", "DAMAGE_CALCULATION" -> "伤害步骤";
            case "BATTLE_END_STEP" -> "战斗结束步骤";
            case "MAIN_PHASE_2" -> "主要阶段2";
            case "END_PHASE" -> "结束阶段";
            default -> phase;
        };
    }
}
