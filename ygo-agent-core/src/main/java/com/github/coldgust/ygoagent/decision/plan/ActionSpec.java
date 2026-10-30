package com.github.coldgust.ygoagent.decision.plan;

import com.github.coldgust.ygodomain.zone.ZoneRef;
import java.util.Locale;

/**
 * 计划中的语义动作（不是协议下标）。
 *
 * @param verb      activate / summon / specialSummon / setMonster / setSpellTrap / attack / place / select
 * @param passcode  主体卡号（区域动作为 0）
 * @param zone      可选：限定区域（owner/kind/sequence）
 */
public record ActionSpec(String verb, long passcode, ZoneRef zone) {

    public ActionSpec {
        verb = verb == null ? "" : verb.trim().toLowerCase(Locale.ROOT);
    }

    public static ActionSpec of(String verb, long passcode) {
        return new ActionSpec(verb, passcode, null);
    }
}
