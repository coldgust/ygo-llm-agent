package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.knowledge.RecordedComboSource;
import com.github.coldgust.ygoplayserver.duel.ComboCatalog;

import java.util.List;
import java.util.Locale;

/**
 * combo 实战录入的 PG 适配器(只读)。
 *
 * <p>保留迁移前的三条语义:
 * <ul>
 *   <li>D1:库不可达/表不存在时静默返回空,不抛(调用方无感跳过);</li>
 *   <li>D4:两条 PG 特化查询(双向 ILIKE、nullable-OR)语义不变,limit 截断留在本层;</li>
 *   <li>D5:两参齐给时做主题归一化复检,宁缺毋滥。</li>
 * </ul>
 */
public final class PgRecordedComboSource implements RecordedComboSource {

    private final ComboRecordRepository repository;
    private final long count;

    private PgRecordedComboSource(ComboRecordRepository repository, long count) {
        this.repository = repository;
        this.count = count;
    }

    /** 运行期探活:表不存在/库不可达时返回 null(不抛)。 */
    public static PgRecordedComboSource tryCreate(ComboRecordRepository repository) {
        try {
            return new PgRecordedComboSource(repository, repository.count());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean available() {
        return count > 0;
    }

    @Override
    public List<Entry> recentByArchetype(String archetype, int limit) {
        if (archetype == null || archetype.isBlank() || count == 0) {
            return List.of();
        }
        int max = Math.max(1, Math.min(limit, 30));
        String pattern = "%" + archetype.trim() + "%";
        try {
            return repository.recentByArchetype(pattern, max).stream()
                    .map(ComboRecordEntity::toEntry)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public List<Entry> search(String archetype, String keyCard, int limit) {
        if (count == 0 || ((archetype == null || archetype.isBlank())
                && (keyCard == null || keyCard.isBlank()))) {
            return List.of();
        }
        int max = Math.max(1, Math.min(limit, 10));
        try {
            String archetypeLike = archetype == null || archetype.isBlank()
                    ? null : "%" + archetype.trim() + "%";
            String cardLike = keyCard == null || keyCard.isBlank()
                    ? null : "%" + keyCard.trim() + "%";
            // 主题双向包含 OR 关键卡牌列包含(key_cards 是逗号分隔的卡名文本)
            List<Entry> matches = repository.search(archetypeLike, cardLike, max).stream()
                    .map(ComboRecordEntity::toEntry)
                    .toList();
            // 两参都给时主题归一化复检:ILIKE 不忽略大小写/下划线差异,
            // 且关键卡命中的记录未必真是该主题的,宁缺毋滥
            if (archetypeLike != null && cardLike != null) {
                matches = matches.stream()
                        .filter(entry -> archetypeMatches(archetype, entry.archetype()))
                        .toList();
            }
            return List.copyOf(matches);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public List<String> archetypes() {
        return ComboCatalog.archetypes(repository);
    }

    private static boolean archetypeMatches(String query, String actual) {
        String a = normalize(actual);
        String q = normalize(query);
        return !a.isEmpty() && !q.isEmpty() && (a.equals(q) || a.contains(q) || q.contains(a));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "").replace("-", "");
    }
}