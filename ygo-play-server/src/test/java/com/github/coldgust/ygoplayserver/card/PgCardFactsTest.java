package com.github.coldgust.ygoplayserver.card;

import com.github.coldgust.ygoagent.decision.planexec.CardFact;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回归：byPasscode 走进程内缓存——同一 passcode 第二次查询不得再打库（热路径一局几百次查名）。 */
class PgCardFactsTest {

    @Test
    void repeatedPasscodeLookupsHitTheDatabaseOnce() {
        CardFact fact = new CardFact(12345L, "闪刀姬-零衣", "Sky Striker Ace - Raye",
                "MONSTER", "怪兽", "战士", "光", 1500, 1500, 4, "效果");
        JdbcClient jdbc = jdbcReturningRows(List.of(fact));

        PgCardFacts facts = PgCardFacts.tryCreate(jdbc);

        assertEquals(Optional.of(fact), facts.byPasscode(12345L));
        assertEquals(Optional.of(fact), facts.byPasscode(12345L));
        // count(*) 一次 + SELECT 一次：第二次 byPasscode 不再产生 SQL
        verify(jdbc, times(2)).sql(anyString());
    }

    @Test
    void missesAreCachedTooSoUnknownCardsDoNotHammerTheDatabase() {
        JdbcClient jdbc = jdbcReturningRows(List.of());

        PgCardFacts facts = PgCardFacts.tryCreate(jdbc);

        assertEquals(Optional.empty(), facts.byPasscode(999999L));
        assertEquals(Optional.empty(), facts.byPasscode(999999L));
        verify(jdbc, times(2)).sql(anyString());
    }

    @Test
    void differentPasscodesAreCachedIndependently() {
        JdbcClient jdbc = jdbcReturningRows(List.of());

        PgCardFacts facts = PgCardFacts.tryCreate(jdbc);

        facts.byPasscode(1L);
        facts.byPasscode(2L);
        verify(jdbc, times(3)).sql(anyString());
    }

    @SuppressWarnings("unchecked")
    private static JdbcClient jdbcReturningRows(List<?> rows) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> countSpec = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<Object> rowSpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(Long.class)).thenReturn(countSpec);
        when(countSpec.single()).thenReturn(0L);
        when(spec.query(any(RowMapper.class))).thenReturn(rowSpec);
        when(rowSpec.list()).thenReturn((List<Object>) rows);
        return jdbc;
    }
}
