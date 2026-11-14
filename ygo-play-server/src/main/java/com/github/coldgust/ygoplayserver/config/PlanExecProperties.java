package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.support.Texts;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 规划器/执行器的运行配置（ygo.plan-exec）。数值/布尔留空 = 走 core 默认，
 * 级联在 {@link #toSettings()}（思考强度由 PipelineSettings 归一化，非法值按未配置）。
 *
 * @param enabled              留空 = 有 api-key 即启用；false = 强制关闭 v6 流水
 * @param apiKey               OpenAI 兼容网关凭据；为空则整个规划器/执行器不启用
 * @param baseUrl              网关地址（留空 = https://api.deepseek.com）
 * @param plannerModel         规划器模型，优先于 llmModel；留空 = 用 llmModel
 * @param llmModel             模型（留空 = deepseek-chat）
 * @param reasoningPlanner     规划器思考强度 off|low|medium|high；留空 = checkpoint 统一 low（推理档）、off = 快档
 * @param reasoningDecision    决策器思考强度；留空 = off（开思考会把 max_tokens 花在思维链上，吃满决策超时）
 * @param cardRagPath          卡牌事实本地查表（PG 不可用时的回退；留空 = data/card_rag/cards.jsonl）
 * @param waitDecisionMs       决策一次最多等待规划器 ms（紧急重规划回复；留空 = 4000）
 * @param firstWaitMs          本回合首个 直答 前等计划的上限 ms（留空 = 7000）
 * @param waitTurnMs           一回合内「为等规划器」的累计上限 ms（留空 = 30000）
 * @param maxCheckpoints       每回合后台 checkpoint 次数上限，成本闸（留空 = 6）
 * @param similarityThreshold  执行器相似分支匹配阈值 0–1，防假相似（留空 = 0.80）
 * @param judgeTimeoutMs     直答 决策硬超时 ms（留空 = 10000）
 * @param pathPlanning         true = 规划器产出可执行路径；留空/false = 顾问模式（只给场面分析/战术/combo）
 * @param rushAnswer           ④升级竞速里规划器抢答；false/0 = 升级只跑直答（留空 = 开）
 */
@ConfigurationProperties(prefix = "ygo.plan-exec")
public record PlanExecProperties(
        Boolean enabled,
        String apiKey,
        String baseUrl,
        String plannerModel,
        String llmModel,
        String reasoningPlanner,
        String reasoningDecision,
        String cardRagPath,
        Integer waitDecisionMs,
        Integer firstWaitMs,
        Integer waitTurnMs,
        Integer maxCheckpoints,
        Double similarityThreshold,
        Integer judgeTimeoutMs,
        Boolean pathPlanning,
        Boolean rushAnswer) {

    /** v6 是否启用：未显式关闭且 api-key 已配置。 */
    public boolean v6Enabled() {
        return !Boolean.FALSE.equals(enabled) && apiKey != null && !apiKey.isBlank();
    }

    /** 与 core 默认级联出完整设置（enabled 恒 true，禁用判断走 {@link #v6Enabled()}）。 */
    public PipelineSettings toSettings() {
        String base = Texts.firstNonBlank(baseUrl, "https://api.deepseek.com");
        String model = Texts.firstNonBlank(plannerModel, llmModel, "deepseek-chat");
        PipelineSettings defaults = PipelineSettings.defaults(
                Path.of(Texts.firstNonBlank(cardRagPath, "data/card_rag/cards.jsonl")),
                base, apiKey, model);
        return PipelineSettings.builder(defaults)
                .reasoningPlanner(reasoningPlanner)
                .reasoningDecision(reasoningDecision)
                // 留空的属性传 null = 继承 core 默认
                .waitPerDecisionMs(waitDecisionMs)
                .firstJudgeWaitMs(firstWaitMs)
                .waitPerTurnMs(waitTurnMs)
                .maxCheckpointsPerTurn(maxCheckpoints)
                .similarityThreshold(similarityThreshold)
                .judgeTimeoutMs(judgeTimeoutMs)
                .pathPlanning(Boolean.TRUE.equals(pathPlanning))
                .plannerRushAnswer(rushAnswer)
                .build();
    }
}
