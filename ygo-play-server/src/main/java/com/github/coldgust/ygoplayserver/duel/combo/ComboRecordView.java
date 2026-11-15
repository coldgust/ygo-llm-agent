package com.github.coldgust.ygoplayserver.duel.combo;

import java.util.List;

/**
 * {@code GET /api/duels/{id}/combo} 的响应体。
 *
 * <p>组件名即 JSON 键,与迁移前 {@code Map<String,Object>} 的键逐一对应(D8);
 * 预览截断由服务层控制({@code STEPS_PREVIEW}),record 本身只是数据载体。
 */
public record ComboRecordView(String duelId, long recordId, String archetype, int count,
                              List<String> stepPreviews, String summary, String keyCards,
                              String summarySource) {

    public ComboRecordView {
        stepPreviews = stepPreviews == null ? List.of() : List.copyOf(stepPreviews);
    }

    public static ComboRecordView of(ComboRecordEntity entity, int previewLimit) {
        List<String> descriptions = entity.stepDescriptions();
        int limit = previewLimit <= 0 ? descriptions.size() : Math.min(previewLimit, descriptions.size());
        return new ComboRecordView(
                entity.getDuelId(),
                entity.getId() == null ? 0L : entity.getId(),
                entity.getArchetype(),
                entity.getStepCount(),
                descriptions.subList(0, limit),
                entity.getSummary(),
                entity.getKeyCards(),
                entity.getSummarySource());
    }
}