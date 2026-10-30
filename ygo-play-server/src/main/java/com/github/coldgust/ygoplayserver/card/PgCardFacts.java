package com.github.coldgust.ygoplayserver.card;

import com.github.coldgust.ygoagent.decision.planexec.CardFact;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 卡牌事实的 PostgreSQL 实现。
 *
 * <p>为什么值得放 PG：内存版虽然单次查找是 O(1)，但每次启动要把 15019 行读进堆（~20MB），
 * 且 web 与 CLI 各读一份；放 PG 后按主键/名字索引查，进程无状态、可共享，
 * 名字模糊搜索也能交给数据库（pg_trgm）。
 *
 * <p>退化策略：表不存在或数据库不可达时 {@link #tryCreate} 返回 null，调用方回退本地 jsonl。
 */
public final class PgCardFacts implements CardFacts {

    private static final String COLUMNS =
            "passcode, name, name_en, card_type, race, attribute, atk, def, level, "
                    + "monster_effect, pendulum_effect";

    private final JdbcClient jdbc;
    private final long count;

    private PgCardFacts(JdbcClient jdbc, long count) {
        this.jdbc = jdbc;
        this.count = count;
    }

    /** 表存在且可查时返回实现；否则 null。 */
    public static PgCardFacts tryCreate(JdbcClient jdbc) {
        try {
            Long total = jdbc.sql("SELECT count(*) FROM card").query(Long.class).single();
            return new PgCardFacts(jdbc, total == null ? 0 : total);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Optional<CardFact> byPasscode(long passcode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM card WHERE passcode = :p")
                .param("p", passcode)
                .query(this::map)
                .list()
                .stream()
                .findFirst();
    }

    @Override
    public List<CardFact> searchByName(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int max = limit <= 0 ? 5 : Math.min(limit, 20);
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("SELECT " + COLUMNS + " FROM card "
                        + "WHERE lower(name) LIKE :q OR lower(name_en) LIKE :q "
                        + "ORDER BY name LIMIT :n")
                .param("q", pattern)
                .param("n", max)
                .query(this::map)
                .list();
    }

    @Override
    public long knownCount() {
        return count;
    }

    private CardFact map(ResultSet rs, int rowNum) throws SQLException {
        String cardType = rs.getString("card_type");
        return new CardFact(
                rs.getLong("passcode"),
                rs.getString("name"),
                nullToEmpty(rs.getString("name_en")),
                kindOf(cardType),
                nullToEmpty(cardType),
                nullToEmpty(rs.getString("race")),
                nullToEmpty(rs.getString("attribute")),
                (Integer) rs.getObject("atk"),
                (Integer) rs.getObject("def"),
                (Integer) rs.getObject("level"),
                mergeEffect(rs.getString("monster_effect"), rs.getString("pendulum_effect")));
    }

    /** 与内存版口径一致：灵摆在前、怪兽效果在后。 */
    private static String mergeEffect(String monster, String pendulum) {
        boolean hasPendulum = pendulum != null && !pendulum.isBlank();
        boolean hasMonster = monster != null && !monster.isBlank();
        if (hasPendulum && hasMonster) {
            return "【灵摆】" + pendulum + "\n【怪兽】" + monster;
        }
        if (hasPendulum) {
            return "【灵摆】" + pendulum;
        }
        return hasMonster ? monster : "";
    }

    private static String kindOf(String cardType) {
        if (cardType == null) {
            return "UNKNOWN";
        }
        if (cardType.contains("怪兽")) {
            return "MONSTER";
        }
        if (cardType.contains("陷阱")) {
            return "TRAP";
        }
        if (cardType.contains("魔法")) {
            return "SPELL";
        }
        return "UNKNOWN";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
