package com.github.coldgust.ygodomain.duel;

/**
 * 怪兽的表示形式（官方术语：Attack Position / Defense Position × face-up / face-down）。
 *
 * <p>基本表示形式为三种：face-up Attack Position、face-up Defense Position、face-down Defense Position；
 * face-down Attack Position 仅在个别卡片效果下出现，协议中保留。
 */
public enum Position {
    FACE_UP_ATTACK,
    FACE_DOWN_ATTACK,
    FACE_UP_DEFENSE,
    FACE_DOWN_DEFENSE;

    public boolean isFaceUp() {
        return this == FACE_UP_ATTACK || this == FACE_UP_DEFENSE;
    }

    public boolean isAttack() {
        return this == FACE_UP_ATTACK || this == FACE_DOWN_ATTACK;
    }

    public boolean isDefense() {
        return this == FACE_UP_DEFENSE || this == FACE_DOWN_DEFENSE;
    }
}
