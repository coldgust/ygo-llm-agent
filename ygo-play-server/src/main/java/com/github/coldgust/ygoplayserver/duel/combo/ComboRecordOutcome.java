package com.github.coldgust.ygoplayserver.duel.combo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE {@code COMBO_RECORDED} 跳过/失败负载。
 *
 * <p>现状是两种形态:{@code {duelId, count, reason}}(没有可记录的操作/缺主题)与
 * {@code {duelId, count, error}}(录入异常)。用类级 {@code NON_NULL} 保持「reason 与 error
 * 二选一」的逐键一致(D8)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComboRecordOutcome(String duelId, int count, String reason, String error) {
}