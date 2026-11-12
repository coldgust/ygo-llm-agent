package com.github.coldgust.ygoagent.llm;

/**
 * LightRAG 知识栈连接参数（规则速查 / 历史对局检索工具共用）。
 *
 * @param url          LightRAG 服务地址；空 = 检索工具不可用（调用会回填错误，模型仍可基于速查继续）
 * @param apiKey       API key（可空）
 * @param timeoutMillis 单次检索超时
 */
public record LightRagSettings(String url, String apiKey, long timeoutMillis) {
}
