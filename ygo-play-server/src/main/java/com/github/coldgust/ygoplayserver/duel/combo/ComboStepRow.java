package com.github.coldgust.ygoplayserver.duel.combo;

/**
 * {@code combo_record.steps} JSONB 数组里的一行。
 *
 * <p>组件名即 JSON 键(camelCase),与迁移前的 {@code Map} 键、既有库内数据完全一致。
 * 与 {@link ComboStepReader.Step} 同构,但那是「从对局记录解析出的步骤」,本 record 是
 * 「落库的 JSONB 行」——两者刻意分开,避免把解析细节泄漏进持久化模型。
 */
public record ComboStepRow(long seq, int turn, String phase, String kind, String desc,
                           long passcode, String fromZone, String toZone,
                           String position, String player) {
}