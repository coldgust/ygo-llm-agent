package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTrace;
import org.springframework.context.annotation.Configuration;

/**
 * 决策侧进程级设置的装配点：把类型化的 {@link AgentProperties} 转成 core 的日志设施配置
 * （core 自身不读环境变量）。未配置时沿用 core 默认：全链路日志关闭。
 */
@Configuration
public class AgentSettingsConfig {

    public AgentSettingsConfig(AgentProperties agent) {
        LlmTrace.configure(agent.verboseLog(), agent.verboseMaxChars());
    }
}
