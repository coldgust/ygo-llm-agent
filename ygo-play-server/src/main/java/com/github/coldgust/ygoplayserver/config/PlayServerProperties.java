package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ygo.play")
public record PlayServerProperties(
        String defaultMove,
        int maxConcurrentDuels) {

    public PlayServerProperties {
        if (defaultMove == null || defaultMove.isBlank()) defaultMove = "second";
        if (maxConcurrentDuels <= 0) maxConcurrentDuels = 1;
    }
}
