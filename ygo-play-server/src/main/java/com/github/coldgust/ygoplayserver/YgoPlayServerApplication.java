package com.github.coldgust.ygoplayserver;

import com.github.coldgust.ygoplayserver.config.LoggingPaths;
import com.github.coldgust.ygoplayserver.config.PlayServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 对局服务：HTTP 启动/查询/停止实机对局。
 * <p>不连数据库、不加载知识库自动配置；决策智能在 ygo-agent（v6 Planner–Executor）。
 */
@SpringBootApplication
@EnableConfigurationProperties(PlayServerProperties.class)
public class YgoPlayServerApplication {

    public static void main(String[] args) {
        // 必须是第一步：logback 在 Spring 容器起来之前就初始化了，
        // 晚于此处的任何属性设置都不会影响文件输出位置。
        LoggingPaths.configure();
        SpringApplication.run(YgoPlayServerApplication.class, args);
    }
}
