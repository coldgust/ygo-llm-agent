package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * v6 配置族（§4）：全部有默认值；由 ygo-play 从 CLI/env/dotenv 解析后传入。
 *
 * @param enabled                  总开关（false 时 DuelRunner 走原 RuleBasedEngine）
 * @param baseUrl                  OpenAI 兼容端点
 * @param apiKey                   API key
 * @param model                    模型名
 * @param reasoningPlanner         规划器思考强度 off|low|medium|high；null = 档位默认（checkpoint 统一 low 推理档，off=快档）
 * @param reasoningDecision        决策器思考强度；null = off（开思考会把 max_tokens 花在思维链上，吃满决策超时）
 * @param waitPerDecisionMs        决策时一次最多等待规划器（紧急重规划回复）
 * @param firstJudgeWaitMs       本回合"第一次要 直答 时"等计划的上限（>waitPerDecisionMs：
 *                                 第一份深度计划能让后续所有 直答 都有主线可参考）
 * @param waitPerTurnMs            一回合内"为等规划器而推迟应答"的累计上限
 * @param maxCheckpointsPerTurn    每回合后台 checkpoint 次数上限（成本闸）
 * @param similarityThreshold      ③ 相似匹配阈值（保守起步）
 * @param judgeTimeoutMs         ④ 路径 直答 的硬超时
 * @param plannerCallTimeoutMs     规划器后台 checkpoint 单次调用超时
 * @param plannerCooldownMs        规划器连续失败后的降级冷却
 * @param cardRagPath              card_rag cards.jsonl 路径
 * @param judgeMaxTokens         直答 输出上限
 * @param pathPlanning             规划器是否产出**可执行路径**（steps/branches）。默认 false：
 *                                 只给场面分析、战术与 combo 的自然语言描述，具体动作由执行器逐点决定。
 *                                 打开后退回 v6 原设计（计划步精确/相似匹配）。
 * @param plannerRushAnswer        ④升级竞速里规划会话是否参与抢答（默认开；关闭后升级只跑直答）
 */
public record PipelineSettings(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        String reasoningPlanner,
        String reasoningDecision,
        int waitPerDecisionMs,
        int firstJudgeWaitMs,
        int waitPerTurnMs,
        int maxCheckpointsPerTurn,
        double similarityThreshold,
        int judgeTimeoutMs,
        int plannerCallTimeoutMs,
        int plannerCooldownMs,
        Path cardRagPath,
        int judgeMaxTokens,
        boolean pathPlanning,
        boolean plannerRushAnswer) {

    public PipelineSettings {
        reasoningPlanner = normalizeReasoning(reasoningPlanner);
        reasoningDecision = normalizeReasoning(reasoningDecision);
        waitPerDecisionMs = Math.max(0, waitPerDecisionMs);
        // waitPerDecisionMs=0 视为"完全不等待"（连整回合那次也关掉）；
        // 其余情况下两个数值互不约束——等待是额外时间，与决策预算无关。
        firstJudgeWaitMs = waitPerDecisionMs <= 0 ? 0 : Math.max(0, firstJudgeWaitMs);
        waitPerTurnMs = Math.max(waitPerDecisionMs, waitPerTurnMs);
        maxCheckpointsPerTurn = Math.max(1, maxCheckpointsPerTurn);
        similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        judgeTimeoutMs = Math.max(500, judgeTimeoutMs);
        plannerCallTimeoutMs = Math.max(1_000, plannerCallTimeoutMs);
        if (plannerCooldownMs <= 0) {
            throw new IllegalArgumentException("plannerCooldownMs 必须为正数"
                    + "（CLI：.env 的 PLANNER_COOLDOWN_MS；server：application.yaml 的 ygo.plan-exec.planner-cooldown-ms）");
        }
        if (judgeMaxTokens <= 0) {
            throw new IllegalArgumentException("judgeMaxTokens 必须为正数"
                    + "（CLI：.env 的 JUDGE_MAX_TOKENS；server：application.yaml 的 ygo.plan-exec.judge-max-tokens）");
        }
    }

    /** apiKey 不进日志（record 自动 toString 会原样打印敏感字段，这里遮蔽；其余字段值如常）。 */
    @Override
    public String toString() {
        return "PipelineSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=***, model=" + model
                + ", reasoningPlanner=" + reasoningPlanner + ", reasoningDecision=" + reasoningDecision
                + ", waitPerDecisionMs=" + waitPerDecisionMs + ", firstJudgeWaitMs=" + firstJudgeWaitMs
                + ", waitPerTurnMs=" + waitPerTurnMs + ", maxCheckpointsPerTurn=" + maxCheckpointsPerTurn
                + ", similarityThreshold=" + similarityThreshold + ", judgeTimeoutMs=" + judgeTimeoutMs
                + ", plannerCallTimeoutMs=" + plannerCallTimeoutMs + ", plannerCooldownMs=" + plannerCooldownMs
                + ", cardRagPath=" + cardRagPath + ", judgeMaxTokens=" + judgeMaxTokens
                + ", pathPlanning=" + pathPlanning + ", plannerRushAnswer=" + plannerRushAnswer + "]";
    }

    /**
     * 调优参数的推荐默认（机器无关：等待预算/阈值/超时/配额——环境相关参数
     * baseUrl/apiKey/model/cardRagPath 由宿主必填传入，缺失由调用方抛异常点名）。
     * 宿主以此为基础做可选覆盖（{@link #builder(PipelineSettings)} 种子式）。
     */
    public static PipelineSettings defaults(Path cardRagPath, String baseUrl, String apiKey, String model) {
        return new PipelineSettings(true, baseUrl, apiKey, model, null, null,
                4_000, 7_000, 30_000, 6, 0.80, 10_000, 45_000, 60_000,
                cardRagPath, 3_000, false, true);
    }

    /**
     * 种子式 Builder：从既有设置出发逐项覆盖，未显式设置（null）的字段继承种子——
     * 调优参数不配 = 用 defaults() 的代码默认，环境相关参数由宿主在种子处必填。
     */
    public static Builder builder(PipelineSettings seed) {
        return new Builder(seed);
    }

    /** 字段收集器：未显式设置的字段继承种子（{@link #builder}）。 */
    public static final class Builder {
        private final PipelineSettings seed;
        private Boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String model;
        private String reasoningPlanner;
        private String reasoningDecision;
        private Integer waitPerDecisionMs;
        private Integer firstJudgeWaitMs;
        private Integer waitPerTurnMs;
        private Integer maxCheckpointsPerTurn;
        private Double similarityThreshold;
        private Integer judgeTimeoutMs;
        private Integer plannerCallTimeoutMs;
        private Integer plannerCooldownMs;
        private Path cardRagPath;
        private Integer judgeMaxTokens;
        private Boolean pathPlanning;
        private Boolean plannerRushAnswer;

        private Builder(PipelineSettings seed) {
            this.seed = seed;
        }

        public Builder enabled(Boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder model(String value) {
            this.model = value;
            return this;
        }

        public Builder reasoningPlanner(String value) {
            this.reasoningPlanner = value;
            return this;
        }

        public Builder reasoningDecision(String value) {
            this.reasoningDecision = value;
            return this;
        }

        public Builder waitPerDecisionMs(Integer value) {
            this.waitPerDecisionMs = value;
            return this;
        }

        public Builder firstJudgeWaitMs(Integer value) {
            this.firstJudgeWaitMs = value;
            return this;
        }

        public Builder waitPerTurnMs(Integer value) {
            this.waitPerTurnMs = value;
            return this;
        }

        public Builder maxCheckpointsPerTurn(Integer value) {
            this.maxCheckpointsPerTurn = value;
            return this;
        }

        public Builder similarityThreshold(Double value) {
            this.similarityThreshold = value;
            return this;
        }

        public Builder judgeTimeoutMs(Integer value) {
            this.judgeTimeoutMs = value;
            return this;
        }

        public Builder plannerCallTimeoutMs(Integer value) {
            this.plannerCallTimeoutMs = value;
            return this;
        }

        public Builder plannerCooldownMs(Integer value) {
            this.plannerCooldownMs = value;
            return this;
        }

        public Builder cardRagPath(Path value) {
            this.cardRagPath = value;
            return this;
        }

        public Builder judgeMaxTokens(Integer value) {
            this.judgeMaxTokens = value;
            return this;
        }

        public Builder pathPlanning(Boolean value) {
            this.pathPlanning = value;
            return this;
        }

        public Builder plannerRushAnswer(Boolean value) {
            this.plannerRushAnswer = value;
            return this;
        }

        public PipelineSettings build() {
            return new PipelineSettings(
                    or(enabled, seed.enabled()),
                    or(baseUrl, seed.baseUrl()),
                    or(apiKey, seed.apiKey()),
                    or(model, seed.model()),
                    or(reasoningPlanner, seed.reasoningPlanner()),
                    or(reasoningDecision, seed.reasoningDecision()),
                    or(waitPerDecisionMs, seed.waitPerDecisionMs()),
                    or(firstJudgeWaitMs, seed.firstJudgeWaitMs()),
                    or(waitPerTurnMs, seed.waitPerTurnMs()),
                    or(maxCheckpointsPerTurn, seed.maxCheckpointsPerTurn()),
                    or(similarityThreshold, seed.similarityThreshold()),
                    or(judgeTimeoutMs, seed.judgeTimeoutMs()),
                    or(plannerCallTimeoutMs, seed.plannerCallTimeoutMs()),
                    or(plannerCooldownMs, seed.plannerCooldownMs()),
                    or(cardRagPath, seed.cardRagPath()),
                    or(judgeMaxTokens, seed.judgeMaxTokens()),
                    or(pathPlanning, seed.pathPlanning()),
                    or(plannerRushAnswer, seed.plannerRushAnswer()));
        }

        private static <T> T or(T override, T fallback) {
            return override == null ? fallback : override;
        }
    }

    /** 思考强度归一化：只接受 off/low/medium/high（大小写不敏感），其余（含 null/空白）= 档位默认。 */
    private static String normalizeReasoning(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("off", "low", "medium", "high").contains(v) ? v : null;
    }

    /** LLM 网关连接参数（baseUrl/apiKey/model 的打包形态，供 chat 栈工厂使用）。 */
    public LlmEndpoint endpoint() {
        return new LlmEndpoint(baseUrl, apiKey, model);
    }

    /** checkpoint 档（推理强度由 LLM_REASONING_PLANNER 推导；medium/high 在此生效）。 */
    public ChatOptions checkpointOptions() {
        String effectiveLevel = reasoningPlanner;
        if ("off".equals(effectiveLevel)) {
            // 顾问模式四段 + 方案比较合计约 900–1100 字，额度给足避免截成半截 JSON；路径模式 JSON 更紧凑
            return ChatOptions.fastPlan(pathPlanning ? 1_200 : 2_600);
        }
        ChatOptions options = ChatOptions.deepPlan(8_000);
        return effectiveLevel == null ? options : options.withReasoning(effectiveLevel);
    }

    /** checkpoint 的模型桶超时（快档走满额，推理档封顶 20s 防止思维链吃光回合预算）。 */
    public Duration checkpointTimeout() {
        return "off".equals(reasoningPlanner)
                ? Duration.ofMillis(plannerCallTimeoutMs)
                : Duration.ofMillis(Math.min(plannerCallTimeoutMs, 20_000));
    }
}
