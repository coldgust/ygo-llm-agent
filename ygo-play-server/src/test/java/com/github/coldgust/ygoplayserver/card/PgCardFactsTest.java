package com.github.coldgust.ygoplayserver.card;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归:byPasscode 走进程内缓存——同一 passcode 第二次查询不得再打库(热路径一局几百次查名);
 * 探活失败必须返回 null(触发装配方回退 jsonl)。
 */
class PgCardFactsTest {

    private static final CardFact FACT = new CardFact(12345L, "闪刀姬-零衣",
            "Sky Striker Ace - Raye", "MONSTER", "怪兽", "战士", "光", 1500, 1500, 4, "效果");

    @Test
    void repeatedPasscodeLookupsHitTheDatabaseOnce() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenReturn(1L);
        CardEntity entity = mock(CardEntity.class);
        when(entity.toCardFact()).thenReturn(FACT);
        when(repository.findByPasscode(12345L)).thenReturn(Optional.of(entity));

        PgCardFacts facts = PgCardFacts.tryCreate(repository);

        assertEquals(Optional.of(FACT), facts.byPasscode(12345L));
        assertEquals(Optional.of(FACT), facts.byPasscode(12345L));
        verify(repository, times(1)).findByPasscode(12345L);
    }

    @Test
    void missesAreCachedTooSoUnknownCardsDoNotHammerTheDatabase() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenReturn(1L);
        when(repository.findByPasscode(999999L)).thenReturn(Optional.empty());

        PgCardFacts facts = PgCardFacts.tryCreate(repository);

        assertEquals(Optional.empty(), facts.byPasscode(999999L));
        assertEquals(Optional.empty(), facts.byPasscode(999999L));
        verify(repository, times(1)).findByPasscode(999999L);
    }

    @Test
    void tryCreateReturnsNullWhenDatabaseUnavailable() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenThrow(new IllegalStateException("connection refused"));

        assertNull(PgCardFacts.tryCreate(repository));
    }

    @Test
    void searchBuildsLowerCasedContainsPatternAndDefaultsToFive() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenReturn(1L);
        when(repository.searchByName(anyString(), any(Limit.class))).thenReturn(List.of());

        PgCardFacts facts = PgCardFacts.tryCreate(repository);
        facts.searchByName("  蓝眼  ", 0);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repository).searchByName(pattern.capture(), limit.capture());
        assertEquals("%蓝眼%", pattern.getValue());
        assertEquals(5, limit.getValue().max());
    }

    @Test
    void searchCapsLimitAtTwentyAndSkipsBlankKeyword() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenReturn(1L);
        when(repository.searchByName(anyString(), any(Limit.class))).thenReturn(List.of());

        PgCardFacts facts = PgCardFacts.tryCreate(repository);
        assertEquals(List.of(), facts.searchByName("   ", 9));
        facts.searchByName("蓝眼", 99);

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repository).searchByName(anyString(), limit.capture());
        assertEquals(20, limit.getValue().max());
    }

    @Test
    void knownCountIsTheProbeSnapshot() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.count()).thenReturn(15019L);

        assertEquals(15019L, PgCardFacts.tryCreate(repository).knownCount());
    }
}