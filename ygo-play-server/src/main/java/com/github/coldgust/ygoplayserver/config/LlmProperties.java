package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 兼容网关凭据（复盘 / combo 摘要等旁路叙事任务用；决策热路径的配置在 ygo.plan-exec）。
 *
 * @param apiKey  API key；留空 = 叙事任务退化为模板/仅存步骤
 * @param baseUrl 网关地址（默认 https://api.deepseek.com）
 * @param model   默认模型（留空 = deepseek-chat）
 */
@ConfigurationProperties(prefix = "ygo.llm")
public record LlmProperties(String apiKey, String baseUrl, String model) {

    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.deepseek.com";
        if (model == null || model.isBlank()) model = "deepseek-chat";
        apiKey = apiKey == null ? "" : apiKey;
    }
}
