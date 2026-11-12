package com.github.coldgust.ygoagent.llm;

/**
 * OpenAI 兼容网关的连接参数。
 *
 * @param baseUrl 网关地址
 * @param apiKey  API key
 * @param model   模型名
 */
public record LlmEndpoint(String baseUrl, String apiKey, String model) {

    @Override
    public String toString() {
        return "LlmEndpoint[model=" + model + ", baseUrl=" + baseUrl + ", apiKey=***]";
    }
}
