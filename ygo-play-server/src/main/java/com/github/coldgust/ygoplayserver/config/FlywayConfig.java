package com.github.coldgust.ygoplayserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 启动迁移的 fail-soft 策略。
 *
 * <p>PG 不可用(或迁移暂时失败)时不阻断 Spring 上下文构建:相关功能按既有降级语义运行
 * ——卡牌事实回退 {@code data/card_rag/cards.jsonl},combo 读路径静默跳过;下次启动重试迁移。
 *
 * <p>PG 的 DDL 是事务性的:失败迁移会完整回滚,{@code flyway_schema_history} 不会留下脏记录,
 * 因此重试是安全的。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    FlywayMigrationStrategy failSoftMigrate() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (RuntimeException e) {
                log.warn("Flyway 迁移失败,跳过(表缺失时相关功能降级;下次启动重试):{}", e.toString());
            }
        };
    }
}