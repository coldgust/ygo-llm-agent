package com.github.coldgust.ygoagent.llm;

/**
 * OpenAI 兼容网关的连接参数。三项都必填：缺了就地抛异常提示配置项，
 * 不在传输层兜默认值（默认值属于宿主装配层——application.yaml / RuntimeConfig）。
 */
public record LlmEndpoint(String baseUrl, String apiKey, String model) {

    public LlmEndpoint {
        baseUrl = require(baseUrl, "LLM baseUrl 未配置：请在宿主配置提供 OpenAI 兼容端点"
                + "（CLI：.env 的 LLM_BASE_URL；server：application.yaml 的 ygo.llm.base-url / ygo.plan-exec.llm-base-url）");
        apiKey = require(apiKey, "LLM apiKey 未配置（CLI：.env 的 LLM_API_KEY；server：application.yaml 的 ygo.llm.api-key）");
        model = require(model, "LLM model 未配置（CLI：.env 的 LLM_MODEL / PLANNER_MODEL；server：application.yaml 的 ygo.llm.model）");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "LlmEndpoint[model=" + model + ", baseUrl=" + baseUrl + ", apiKey=***]";
    }
}
