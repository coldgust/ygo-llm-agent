package com.github.coldgust.ygoplayserver;

import com.github.coldgust.ygoplayserver.config.AgentProperties;
import com.github.coldgust.ygoplayserver.config.ComboProperties;
import com.github.coldgust.ygoplayserver.config.DuelSummaryProperties;
import com.github.coldgust.ygoplayserver.config.LightRagProperties;
import com.github.coldgust.ygoplayserver.config.LoggingPaths;
import com.github.coldgust.ygoplayserver.config.LlmProperties;
import com.github.coldgust.ygoplayserver.config.PlanExecProperties;
import com.github.coldgust.ygoplayserver.config.PlayServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        PlayServerProperties.class,
        LightRagProperties.class,
        AgentProperties.class,
        PlanExecProperties.class,
        LlmProperties.class,
        DuelSummaryProperties.class,
        ComboProperties.class,
})
public class YgoPlayServerApplication {

    public static void main(String[] args) {
        // 必须是第一步：logback 在 Spring 容器起来之前就初始化了，
        // 晚于此处的任何属性设置都不会影响文件输出位置。
        LoggingPaths.configure();
        SpringApplication.run(YgoPlayServerApplication.class, args);
    }
}
