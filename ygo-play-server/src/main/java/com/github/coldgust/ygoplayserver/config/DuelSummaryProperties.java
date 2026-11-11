package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 赛后复盘（写进 LightRAG，供 search_duels 检索）。
 *
 * @param enabled           false = 完全不写复盘（默认 true）
 * @param mode              llm（默认，模型写复盘）| template（不调模型，只记事实）| off
 * @param model             复盘模型；留空 = 用 ygo.llm.model
 * @param timeoutMs         单次复盘超时 ms（旁路任务，可比决策宽；默认 60000）
 * @param maxTokens         复盘生成 max_tokens（默认 2000）
 * @param narrativeMaxChars 逐回合记录的字符预算，喂给模型的素材上限（默认 8000）
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
        if (enabled == null) enabled = true;
        if (mode == null || mode.isBlank()) mode = "llm";
        if (model != null && model.isBlank()) model = null;
        if (timeoutMs == null || timeoutMs <= 0) timeoutMs = 60_000L;
        if (maxTokens == null || maxTokens <= 0) maxTokens = 2_000;
        if (narrativeMaxChars == null || narrativeMaxChars <= 0) narrativeMaxChars = 8_000;
    }

    public static DuelSummaryProperties defaults() {
        return new DuelSummaryProperties(null, null, null, null, null, null);
    }
}
