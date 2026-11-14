package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 赛后复盘（写进 LightRAG，供 search_duels 检索）。
 * 默认值承载在 application.yaml（${DUEL_SUMMARY_*:默认} 桥接），Java 不内设；必填缺失 → 抛点名。
 *
 * @param enabled           false = 完全不写复盘
 * @param mode              llm（模型写复盘）| template（不调模型，只记事实）| off
 * @param model             复盘模型；留空 = 用 ygo.llm.model（回落语义保留）
 * @param timeoutMs         单次复盘超时 ms（旁路任务，可比决策宽）
 * @param maxTokens         复盘生成 max_tokens
 * @param narrativeMaxChars 逐回合记录的字符预算，喂给模型的素材上限
 */
@ConfigurationProperties(prefix = "ygo.summary")
public record DuelSummaryProperties(
        Boolean enabled,
        String mode,
        String model,
        Long timeoutMs,
        Integer maxTokens,
        Integer narrativeMaxChars) {

    public DuelSummaryProperties {
        if (enabled == null) {
            throw missing("ygo.summary.enabled (DUEL_SUMMARY_ENABLED)");
        }
        if (mode == null || mode.isBlank()) {
            throw missing("ygo.summary.mode (DUEL_SUMMARY_MODE)");
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            throw missing("ygo.summary.timeout-ms (DUEL_SUMMARY_TIMEOUT_MS)");
        }
        if (maxTokens == null || maxTokens <= 0) {
            throw missing("ygo.summary.max-tokens (DUEL_SUMMARY_MAX_TOKENS)");
        }
        if (narrativeMaxChars == null || narrativeMaxChars <= 0) {
            throw missing("ygo.summary.narrative-max-chars (DUEL_NARRATIVE_MAX_CHARS)");
        }
    }

    private static IllegalStateException missing(String key) {
        return new IllegalStateException("缺少必填配置 " + key
                + "（application.yaml 的 ${ENV:默认} 桥接缺失或值为空）");
    }
}
