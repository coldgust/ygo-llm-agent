package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoplayserver.duel.DuelSummaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 赛后复盘的装配点。
 *
 * <p>单独拿出来是因为它有两个"可选依赖"：LLM 凭据与 LightRAG。任何一种缺失都不该让服务起不来——
 * 缺 LLM 就只写模板摘要，缺 LightRAG 就只记日志。用工厂而不是在 {@code @Service} 上做字段注入，
 * 是为了让"哪些情况下会退化"在一处看得见。
 */
@Configuration
public class SummaryConfig {

    @Bean
    public DuelSummaryService duelSummaryService(Environment environment) {
        return DuelSummaryService.fromEnvironment(environment);
    }
}
