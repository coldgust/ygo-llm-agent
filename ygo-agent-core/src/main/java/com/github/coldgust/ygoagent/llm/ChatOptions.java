package com.github.coldgust.ygoagent.llm;

import java.time.Duration;

/**
 * 调用档位（调用点词汇，与 wire 参数的映射见 {@link TierParameters}）。
 * <p>五档工厂：规划器 checkpoint 用 fastPlan/deepPlan（由
 * {@code PipelineSettings.checkpointOptions} 推导），决策器判断/抢答用 decision（2–4s 硬预算），
 * 赛后复盘等叙事任务用 narrative（无 JSON、无工具）。
 */
public record ChatOptions(double temperature, int maxTokens, boolean jsonMode, Duration timeout,
                          String reasoning) {

    public static ChatOptions fastPlan(int maxTokens, Duration timeout) {
        return new ChatOptions(0.3, Math.max(maxTokens, 1_200), true, timeout, "off");
    }

    public static ChatOptions deepPlan(int maxTokens, Duration timeout) {
        return new ChatOptions(0.4, Math.max(maxTokens, 8_000), true, timeout, "low");
    }

    public static ChatOptions decision(int maxTokens, Duration timeout) {
        // 从候选里挑一个：关掉思考，2–4 秒内必须给出 JSON
        return new ChatOptions(0.2, maxTokens, true, timeout, "off");
    }

    /**
     * 叙事档（赛后复盘、对局总结这类"写一段话"的任务）。
     *
     * <p>和决策档的三点不同：不要 JSON 模式（输出是 Markdown）、温度略高（允许措辞变化，
     * 但仍在 0.3 以内以免自由发挥成小说）、不需要 tools。这条路不在决策热路径上，
     * 超时可以给得比 直答 宽。
     */
    public static ChatOptions narrative(int maxTokens, Duration timeout) {
        return new ChatOptions(0.3, Math.max(maxTokens, 800), false, timeout, "off");
    }

    /** 去掉思考参数（端点不认 thinking/reasoning_effort 时的退路）。 */
    public ChatOptions withoutReasoning() {
        return new ChatOptions(temperature, maxTokens, jsonMode, timeout, null);
    }

    public ChatOptions withReasoning(String override) {
        String level = override == null ? "" : override.trim();
        if (level.isEmpty() || level.equalsIgnoreCase(reasoning)) {
            return this;
        }
        int tokens = "off".equalsIgnoreCase(level) ? maxTokens : Math.max(maxTokens, 4_000);
        return new ChatOptions(temperature, tokens, jsonMode, timeout, level.toLowerCase());
    }
}
