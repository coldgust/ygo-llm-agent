package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 兼容网关凭据（复盘 / combo 摘要等旁路叙事任务用；决策热路径的配置在 ygo.plan-exec）。
 * 默认值承载在 application.yaml（${LLM_BASE_URL:...} / ${LLM_MODEL:...}），Java 不内设。
 *
 * @param apiKey  API key；留空 = 叙事任务退化为模板/仅存步骤
 * @param baseUrl 网关地址
 * @param model   默认模型
 */
@ConfigurationProperties(prefix = "ygo.llm")
public record LlmProperties(String apiKey, String baseUrl, String model) {

    public LlmProperties {
        apiKey = apiKey == null ? "" : apiKey;
    }
}
