package com.github.coldgust.ygoplayserver.duel.combo;

import java.util.List;

/**
 * SSE {@code COMBO_RECORDED} 成功负载。
 *
 * <p>与 {@link ComboRecordView} 的差别只在没有 {@code keyCards}:迁移前的成功事件 payload
 * 就没有该键,这里刻意保持逐键一致(D8)。
 */
public record ComboRecordedEvent(String duelId, long recordId, String archetype, int count,
                                 List<String> stepPreviews, String summary,
                                 String summarySource) {

    public ComboRecordedEvent {
        stepPreviews = stepPreviews == null ? List.of() : List.copyOf(stepPreviews);
    }
}