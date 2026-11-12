package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 决策侧进程级设置的装配点：把类型化的 {@link AgentProperties} 转成 core 的日志设施
 * （core 自身不读环境变量）。未配置时沿用 core 默认：全链路日志关闭。
 */
@Configuration
public class AgentSettingsConfig {

    @Bean
    public VerboseLog verboseLog(AgentProperties agent) {
        return new VerboseLog(VerboseLogSettings.parse(agent.verboseLog(), agent.verboseMaxChars()));
    }
}
