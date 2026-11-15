package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.knowledge.RecordedComboSource;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.List;

/**
 * combo 实战录入表 {@code combo_record} 的实体。
 *
 * <p>{@code steps} 走显式 {@link ComboStepListConverter} + {@code @JdbcTypeCode(JSON)}:
 * 转换器把 record 列表变成 JSON 文本(PG 侧按 jsonb 绑定),读取时反向解析并对坏 JSON 容错。
 *
 * <p>{@code recorded_at} 刻意不映射:列有 {@code DEFAULT now()},INSERT 省略即由库填;
 * 本实体目前没有任何行为用到它(排序走原生 SQL 的库值)。
 */
@Entity
@Table(name = "combo_record")
public class ComboRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "duel_id", nullable = false)
    private String duelId;

    @Column(nullable = false)
    private String archetype;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Convert(converter = ComboStepListConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ComboStepRow> steps = List.of();

    private String summary;

    @Column(name = "key_cards")
    private String keyCards;

    @Column(name = "summary_source", nullable = false)
    private String summarySource = "NONE";

    protected ComboRecordEntity() {
        // JPA
    }

    /**
     * 唯一写入口:{@code stepCount} 由 {@code steps.size()} 派生,避免两个事实源。
     * id 由 IDENTITY 在 {@code save} 时回填(替代迁移前的 {@code RETURNING id})。
     */
    public static ComboRecordEntity record(String duelId, String archetype,
                                           List<ComboStepRow> steps, String summary,
                                           String keyCards, String summarySource) {
        ComboRecordEntity entity = new ComboRecordEntity();
        entity.duelId = duelId;
        entity.archetype = archetype;
        entity.steps = steps == null ? List.of() : List.copyOf(steps);
        entity.stepCount = entity.steps.size();
        entity.summary = summary;
        entity.keyCards = keyCards;
        entity.summarySource = summarySource;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public String getDuelId() {
        return duelId;
    }

    public String getArchetype() {
        return archetype;
    }

    public int getStepCount() {
        return stepCount;
    }

    public String getSummary() {
        return summary;
    }

    public String getKeyCards() {
        return keyCards;
    }

    public String getSummarySource() {
        return summarySource;
    }

    /** 关键卡牌列是逗号/顿号分隔文本(历史格式,不迁 JSONB)——切分逻辑只留这一处。 */
    public List<String> keyCardList() {
        if (keyCards == null || keyCards.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keyCards.split("[,，、]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** 逐步操作的自然语言描述;坏 JSON 已被转换器挡成空列表。 */
    public List<String> stepDescriptions() {
        return steps.stream()
                .map(ComboStepRow::desc)
                .filter(desc -> desc != null && !desc.isBlank())
                .toList();
    }

    public RecordedComboSource.Entry toEntry() {
        return new RecordedComboSource.Entry(
                id == null ? 0L : id,
                archetype,
                stepCount,
                summary,
                keyCardList(),
                stepDescriptions());
    }
}