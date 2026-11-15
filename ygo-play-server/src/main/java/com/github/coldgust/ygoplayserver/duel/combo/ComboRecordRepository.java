package com.github.coldgust.ygoplayserver.duel.combo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code combo_record} 仓库。
 *
 * <p>用 {@code CrudRepository} 而非 {@code JpaRepository}:只需要 save 与查询,
 * 不需要分页/flush 语义。
 */
public interface ComboRecordRepository extends CrudRepository<ComboRecordEntity, Long> {

    /** 某局最新一条录入(同一 duel_id 可能多次录入,取 id 最大)。 */
    Optional<ComboRecordEntity> findFirstByDuelIdOrderByIdDesc(String duelId);

    @Query("select distinct r.archetype from ComboRecordEntity r")
    List<String> findDistinctArchetypes();

    /**
     * 主题双向包含匹配(D4):PG 双向 ILIKE 保留为静态原生 SQL——JPQL 无 ILIKE,
     * 且 {@code :like} 同时出现在运算符两侧是刻意行为。
     */
    @Query(value = """
            SELECT * FROM combo_record
            WHERE archetype ILIKE :like OR :like ILIKE ('%' || archetype || '%')
            ORDER BY recorded_at DESC, id DESC
            LIMIT :n""", nativeQuery = true)
    List<ComboRecordEntity> recentByArchetype(@Param("like") String like, @Param("n") int n);

    /**
     * find_combos 检索(D4):主题双向包含 OR 关键卡牌列包含;null 由适配器决定,SQL 本身不拼。
     */
    @Query(value = """
            SELECT * FROM combo_record
            WHERE (:archLike IS NOT NULL AND (archetype ILIKE :archLike
                      OR :archLike ILIKE ('%' || archetype || '%')))
               OR (:cardLike IS NOT NULL AND key_cards ILIKE :cardLike)
            ORDER BY recorded_at DESC, id DESC
            LIMIT :cap""", nativeQuery = true)
    List<ComboRecordEntity> search(@Param("archLike") String archLike,
                                   @Param("cardLike") String cardLike,
                                   @Param("cap") int cap);
}