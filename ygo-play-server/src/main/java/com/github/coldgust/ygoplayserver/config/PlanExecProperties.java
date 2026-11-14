package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.support.Texts;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 规划器/执行器的运行配置（ygo.plan-exec）。键来自 .env / 环境变量，经 application.yaml 的
 * {@code ${ENV:...}} 桥接进入。默认值分级：调优旋钮（等待预算/阈值/超时/配额）机器无关，
 * 代码默认在 core {@code PipelineSettings.defaults}（本 record 字段 null = 继承）；
 * 环境相关项（网关地址/模型/卡库路径）默认在 application.yaml，Java 必填校验缺失即抛点名。
 *
 * @param enabled              留空 = 有 api-key 即启用；false = 强制关闭 v6 流水
 * @param apiKey               OpenAI 兼容网关凭据；为空则整个规划器/执行器不启用
 * @param baseUrl              网关地址（yaml 默认 https://api.deepseek.com）
 * @param plannerModel         规划器模型，优先于 llmModel；留空 = 用 llmModel
 * @param llmModel             模型（yaml 默认 deepseek-chat）
 * @param reasoningPlanner     规划器思考强度 off|low|medium|high；留空 = checkpoint 统一 low（推理档）、off = 快档
 * @param reasoningDecision    决策器思考强度；留空 = off（开思考会把 max_tokens 花在思维链上，吃满决策超时）
 * @param cardRagPath          卡牌事实本地查表（PG 不可用时的回退）
 * @param waitDecisionMs       决策一次最多等待规划器 ms（紧急重规划回复）
 * @param firstWaitMs          本回合首个 直答 前等计划的上限 ms
 * @param waitTurnMs           一回合内「为等规划器」的累计上限 ms
 * @param maxCheckpoints       每回合后台 checkpoint 次数上限，成本闸
 * @param similarityThreshold  执行器相似分支匹配阈值 0–1，防假相似
 * @param judgeTimeoutMs       直答 决策硬超时 ms
 * @param plannerCallTimeoutMs 规划器后台 checkpoint 单次调用超时 ms
 * @param plannerCooldownMs    规划器连续失败后的降级冷却 ms
 * @param judgeMaxTokens       直答输出上限 tokens
 * @param pathPlanning         true = 规划器产出可执行路径；留空/false = 顾问模式（只给场面分析/战术/combo）
 * @param rushAnswer           ④升级竞速里规划器抢答；false/0 = 升级只跑直答（yaml 默认开）
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
        Integer plannerCallTimeoutMs,
        Integer plannerCooldownMs,
        Integer judgeMaxTokens,
        Boolean pathPlanning,
        Boolean rushAnswer) {

    /** v6 是否启用：未显式关闭且 api-key 已配置；显式开启但缺 key → 抛点名。 */
    public boolean v6Enabled() {
        if (Boolean.TRUE.equals(enabled) && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("PLAN_EXEC_ENABLED=true 但 LLM_API_KEY 未配置："
                    + "填入网关凭据，或去掉显式开启（缺席 = 有 key 才启用）");
        }
        return !Boolean.FALSE.equals(enabled) && apiKey != null && !apiKey.isBlank();
    }

    /** 与调优默认级联出完整设置（enabled 恒 true，禁用判断走 {@link #v6Enabled()}）；
     * 环境相关项（地址/模型/卡库路径）必填缺失 → 抛点名，调优旋钮 null = 继承 core 代码默认。 */
    public PipelineSettings toSettings() {
        String model = Texts.firstNonBlank(plannerModel, llmModel);
        PipelineSettings defaults = PipelineSettings.defaults(
                Path.of(required(cardRagPath, "ygo.plan-exec.card-rag-path / CARD_RAG_PATH")),
                required(baseUrl, "ygo.plan-exec.base-url / LLM_BASE_URL"),
                apiKey,
                required(model, "ygo.plan-exec.planner-model / llm-model"));
        return PipelineSettings.builder(defaults)
                .reasoningPlanner(reasoningPlanner)
                .reasoningDecision(reasoningDecision)
                .waitPerDecisionMs(waitDecisionMs)
                .firstJudgeWaitMs(firstWaitMs)
                .waitPerTurnMs(waitTurnMs)
                .maxCheckpointsPerTurn(maxCheckpoints)
                .similarityThreshold(similarityThreshold)
                .judgeTimeoutMs(judgeTimeoutMs)
                .plannerCallTimeoutMs(plannerCallTimeoutMs)
                .plannerCooldownMs(plannerCooldownMs)
                .judgeMaxTokens(judgeMaxTokens)
                .pathPlanning(pathPlanning)
                .plannerRushAnswer(rushAnswer)
                .build();
    }

    // ---- 环境相关必填校验（调优参数有代码默认，不走此路径）----

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必填配置 " + key
                    + "（application.yaml 的 ${ENV:默认} 桥接缺失或值为空）");
        }
        return value;
    }

    /** apiKey 不进日志（record 自动 toString 会原样打印敏感字段，这里遮蔽）；全量字段值经具名访问器获取。 */
    @Override
    public String toString() {
        return "PlanExecProperties[enabled=" + enabled + ", apiKey=***, baseUrl=" + baseUrl
                + ", plannerModel=" + plannerModel + ", llmModel=" + llmModel
                + ", reasoningPlanner=" + reasoningPlanner + ", reasoningDecision=" + reasoningDecision
                + ", cardRagPath=" + cardRagPath + ", pathPlanning=" + pathPlanning + ", rushAnswer=" + rushAnswer
                + ", waitDecisionMs=" + waitDecisionMs + ", firstWaitMs=" + firstWaitMs + ", waitTurnMs=" + waitTurnMs
                + ", maxCheckpoints=" + maxCheckpoints + ", similarityThreshold=" + similarityThreshold
                + ", judgeTimeoutMs=" + judgeTimeoutMs + ", plannerCallTimeoutMs=" + plannerCallTimeoutMs
                + ", plannerCooldownMs=" + plannerCooldownMs + ", judgeMaxTokens=" + judgeMaxTokens + "]";
    }
}
