package com.github.coldgust.ygoplayserver;

import com.github.coldgust.ygoplayserver.card.CardRepository;
import com.github.coldgust.ygoplayserver.duel.combo.ComboRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 钉住「PG 不可达也能启动」这条降级链:
 * Hikari 懒池(initialization-fail-timeout=-1)→ Flyway 迁移失败被 fail-soft 策略吞掉
 * → Hibernate EMF 因方言已钉死 + 关闭元数据访问而无需连库 → 上下文照常构建。
 *
 * <p>没有这条测试,日后打开 ddl-auto=validate、删掉 jakarta.persistence.database-product-name、
 * 或升级 Hibernate 恢复启动期元数据探测,都会静默破坏 D1——元数据访问关闭时,方言由
 * JPA 标准属性 database-product-name/-major-version/-minor-version 显式声明(Hibernate 7 官方指南做法)。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/ygo",
                "spring.datasource.hikari.connection-timeout=1000"
        })
class BootWithoutPostgresTest {

    @Autowired
    CardRepository cardRepository;

    @Autowired
    ComboRecordRepository comboRecordRepository;

    @Test
    void contextBuildsWithoutPostgres() {
        assertNotNull(cardRepository);
        assertNotNull(comboRecordRepository);
    }
}