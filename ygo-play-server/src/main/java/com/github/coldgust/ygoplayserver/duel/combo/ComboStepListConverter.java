package com.github.coldgust.ygoplayserver.duel.combo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * {@code List<ComboStepRow>} ↔ JSONB 文本的显式转换器。
 *
 * <p>为什么用转换器而不是让 Hibernate 的 JSON FormatMapper 直接反序列化:
 * 坏 JSON 的容错(D6)只能在转换器里做——FormatMapper 抛异常时无法按行降级,
 * 一条坏数据会让整个查询失败。
 *
 * <p>写侧不吞错:序列化失败交给 {@code ComboRecordService#record} 的全 catch → SSE 错误(D7)。
 * 读侧吞错:坏 JSON 退化为空步骤列表,摘要等其余字段仍可用(D6)。
 */
@Converter(autoApply = false)
public class ComboStepListConverter implements AttributeConverter<List<ComboStepRow>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<ComboStepRow> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<ComboStepRow> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            // 用 constructCollectionType 而非 TypeReference,避开 Jackson 3 泛型描点的实现差异。
            // 注意 JSON 文本 null 会解析成 null,必须归一成空列表,否则实体方法会 NPE。
            List<ComboStepRow> parsed = MAPPER.readValue(dbData,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ComboStepRow.class));
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}