package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.decision.planexec.llm.RecordedComboSource;
import com.github.coldgust.ygoplayserver.duel.ComboCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class PgRecordedComboSource implements RecordedComboSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final long count;

    private PgRecordedComboSource(JdbcClient jdbc, long count) {
        this.jdbc = jdbc;
        this.count = count;
    }

    public static PgRecordedComboSource tryCreate(JdbcClient jdbc) {
        try {
            Long total = jdbc.sql("SELECT count(*) FROM combo_record").query(Long.class).single();
            return new PgRecordedComboSource(jdbc, total == null ? 0 : total);
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
            return jdbc.sql("""
                            SELECT id, archetype, step_count, summary, key_cards, steps FROM combo_record
                            WHERE archetype ILIKE :like OR :like ILIKE ('%' || archetype || '%')
                            ORDER BY recorded_at DESC, id DESC LIMIT :n""")
                    .param("like", pattern)
                    .param("n", max)
                    .query(this::mapRow)
                    .list();
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
            // 主题双向包含 OR 关键卡牌列包含（key_cards 是逗号分隔的卡名文本）
            List<Entry> matches = jdbc.sql("""
                            SELECT id, archetype, step_count, summary, key_cards, steps FROM combo_record
                            WHERE (:archLike IS NOT NULL AND (archetype ILIKE :archLike
                                      OR :archLike ILIKE ('%' || archetype || '%')))
                               OR (:cardLike IS NOT NULL AND key_cards ILIKE :cardLike)
                            ORDER BY recorded_at DESC, id DESC LIMIT :cap""")
                    .param("archLike", archetypeLike)
                    .param("cardLike", cardLike)
                    .param("cap", max)
                    .query(this::mapRow)
                    .list();
            // 两参都给时主题归一化复检：ILIKE 不忽略大小写/下划线差异，
            // 且关键卡命中的记录未必真是该主题的，宁缺毋滥
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
        return ComboCatalog.archetypes(jdbc);
    }

    private Entry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Entry(
                rs.getLong("id"),
                rs.getString("archetype"),
                rs.getInt("step_count"),
                rs.getString("summary"),
                keyCards(rs.getString("key_cards")),
                stepDescriptions(rs.getString("steps")));
    }

    private static List<String> keyCards(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，、]"))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
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

    private static List<String> stepDescriptions(String stepsJson) {
        List<String> descs = new ArrayList<>();
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = MAPPER.readTree(stepsJson);
            if (array.isArray()) {
                array.forEach(node -> {
                    String desc = node.path("desc").asText("");
                    if (!desc.isBlank()) {
                        descs.add(desc);
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // 坏 JSON：给空列表，摘要仍在
        }
        return List.copyOf(descs);
    }
}
