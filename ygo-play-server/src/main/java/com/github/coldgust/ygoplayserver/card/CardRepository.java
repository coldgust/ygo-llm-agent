package com.github.coldgust.ygoplayserver.card;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code card} 表的只读仓库。
 *
 * <p>刻意不继承 {@code JpaRepository}/{@code CrudRepository}:card 表由 ingest 写入、Java 侧只读,
 * 接口暴露 save/delete 只会误导后来者。{@code count} 用显式 JPQL 声明,不依赖 PartTree 解析。
 */
public interface CardRepository extends Repository<CardEntity, Long> {

    @Query("select count(c) from CardEntity c")
    long count();

    Optional<CardEntity> findByPasscode(long passcode);

    /**
     * 按卡名/英文名模糊搜索(忽略大小写,包含匹配)。
     *
     * <p>关键字不转义 {@code %}/{@code _}——与迁移前行为一致(用户输入即通配符);
     * {@code lower()} 命中 {@code idx_card_name_lower} 函数索引。
     */
    @Query("""
            select c from CardEntity c
            where lower(c.name) like :q or lower(c.nameEn) like :q
            order by c.name""")
    List<CardEntity> searchByName(@Param("q") String q, Limit limit);
}