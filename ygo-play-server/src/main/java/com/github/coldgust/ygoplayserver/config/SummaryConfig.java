package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoplayserver.duel.DuelSummaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class SummaryConfig {

    @Bean
    public DuelSummaryService duelSummaryService(Environment environment) {
        return DuelSummaryService.fromEnvironment(environment);
    }
}
