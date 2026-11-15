package com.github.coldgust.ygoplayserver.card;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 卡牌事实的 PG 适配器:查询走 {@link CardRepository},行 → 领域对象的推导在
 * {@link CardEntity#toCardFact()}。
 *
 * <p>保留迁移前的两条语义:
 * <ul>
 *   <li>D3:一局几百次按 passcode 查名全走 Caffeine,{@code Optional.empty()} 也缓存(防穿透);</li>
 *   <li>D1:{@code tryCreate} 探活失败(表不存在/库不可达)返回 null,由装配方回退 jsonl。</li>
 * </ul>
 */
public final class PgCardFacts implements CardFacts {

    private final CardRepository repository;
    private final long count;
    /** card 表对局期间不变:热路径(labelOf/候选卡面)一局几百次按 passcode 查名,全走缓存;
     *  空 Optional 也缓存(防穿透),过期与 cards-pg 再入库节奏对齐。 */
    private final Cache<Long, Optional<CardFact>> byPasscodeCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    private PgCardFacts(CardRepository repository, long count) {
        this.repository = repository;
        this.count = count;
    }

    /** 运行期探活:表不存在/库不可达时返回 null(不抛),调用方据此回退本地 jsonl。 */
    public static PgCardFacts tryCreate(CardRepository repository) {
        try {
            return new PgCardFacts(repository, repository.count());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Optional<CardFact> byPasscode(long passcode) {
        return byPasscodeCache.get(passcode, this::loadByPasscode);
    }

    private Optional<CardFact> loadByPasscode(long passcode) {
        return repository.findByPasscode(passcode).map(CardEntity::toCardFact);
    }

    @Override
    public List<CardFact> searchByName(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int max = limit <= 0 ? 5 : Math.min(limit, 20);
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return repository.searchByName(pattern, Limit.of(max)).stream()
                .map(CardEntity::toCardFact)
                .toList();
    }

    @Override
    public long knownCount() {
        return count;
    }
}