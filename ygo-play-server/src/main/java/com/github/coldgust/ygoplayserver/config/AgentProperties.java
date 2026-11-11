package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 决策侧进程级开关：全链路日志（LlmTrace）。
 * 全部 String——取值合法性与默认由 core 的 LlmTrace 自己解析，这里只承载。
 *
 * @param verboseLog       全链路日志开关：true = LLM 请求/响应原文、工具调用、决策流水、轨迹事件打进日志（默认关）
 * @param verboseMaxChars  单条日志内容最长字数；0 = 不截断（默认）
 */
@ConfigurationProperties(prefix = "ygo.agent")
public record AgentProperties(
        String verboseLog,
        String verboseMaxChars) {
}
