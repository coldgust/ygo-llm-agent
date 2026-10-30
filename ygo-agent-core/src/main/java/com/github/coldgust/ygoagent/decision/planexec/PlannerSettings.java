package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;

import java.nio.file.Path;
import java.time.Duration;

/**
 * v6 配置族（§4）：全部有默认值；由 ygo-play 从 CLI/env/dotenv 解析后传入。
 *
 * @param enabled                  总开关（false 时 DuelRunner 走原 DefaultDecisionPort）
 * @param baseUrl                  OpenAI 兼容端点
 * @param apiKey                   API key
 * @param model                    模型名
 * @param waitPerDecisionMs        决策时一次最多等待规划器（紧急重规划回复）
 * @param firstOneshotWaitMs       本回合"第一次要 one-shot 时"等计划的上限（>waitPerDecisionMs：
 *                                 第一份深度计划能让后续所有 one-shot 都有主线可参考）
 * @param waitPerTurnMs            一回合内"为等规划器而推迟应答"的累计上限
 * @param maxCheckpointsPerTurn    每回合后台 checkpoint 次数上限（成本闸）
 * @param similarityThreshold      ③ 相似匹配阈值（保守起步）
 * @param oneshotTimeoutMs         ④ 路径 one-shot 的硬超时
 * @param plannerCallTimeoutMs     规划器后台 checkpoint 单次调用超时
 * @param plannerCooldownMs        规划器连续失败后的降级冷却
 * @param cardRagPath              card_rag cards.jsonl 路径
 * @param oneshotMaxTokens         one-shot 输出上限
 * @param pathPlanning             规划器是否产出**可执行路径**（steps/branches）。默认 false：
 *                                 只给场面分析、战术与 combo 的自然语言描述，具体动作由执行器逐点决定。
 *                                 打开后退回 v6 原设计（计划步精确/相似匹配）。
 * @param plannerRushAnswer        ④升级竞速里规划会话是否参与抢答（默认开；关闭后升级只跑单轮直答）
 */
public record PlannerSettings(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int waitPerDecisionMs,
        int firstOneshotWaitMs,
        int waitPerTurnMs,
        int maxCheckpointsPerTurn,
        double similarityThreshold,
        int oneshotTimeoutMs,
        int plannerCallTimeoutMs,
        int plannerCooldownMs,
        Path cardRagPath,
        int oneshotMaxTokens,
        boolean pathPlanning,
        boolean plannerRushAnswer) {

    public PlannerSettings {
        waitPerDecisionMs = Math.max(0, waitPerDecisionMs);
        // waitPerDecisionMs=0 视为"完全不等待"（连整回合那次也关掉）；
        // 其余情况下两个数值互不约束——等待是额外时间，与决策预算无关。
        firstOneshotWaitMs = waitPerDecisionMs <= 0 ? 0 : Math.max(0, firstOneshotWaitMs);
        waitPerTurnMs = Math.max(waitPerDecisionMs, waitPerTurnMs);
        maxCheckpointsPerTurn = Math.max(1, maxCheckpointsPerTurn);
        similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        oneshotTimeoutMs = Math.max(500, oneshotTimeoutMs);
        plannerCallTimeoutMs = Math.max(1_000, plannerCallTimeoutMs);
    }

    public static PlannerSettings defaults(Path cardRagPath, String baseUrl, String apiKey, String model) {
        return new PlannerSettings(true, baseUrl, apiKey, model,
                4_000, 7_000, 30_000, 6, 0.80, 10_000, 45_000, 60_000,
                cardRagPath, 3_000, false, true);
    }

    /**
     * 规划器 checkpoint（锚点/紧急重规划/增量修订）的调用选项，由生效思考强度一档决定
     * （{@code LLM_REASONING_PLANNER}，{@code null}=未配置 → 默认 low）：
     * <ul>
     *   <li>{@code off} → 快档：thinking=off、~2s、小 token、不封 20s——锚点快出计划的旧行为；</li>
     *   <li>low/medium/high → 推理档：思维链计入 max_tokens 必须给足 8k；预算封顶 20s，
     *       避免一次慢调用拖住后续 checkpoint。代价（实测）：low 档大锚点 7–8s+，
     *       首动作的等待窗口（firstOneshotWaitMs，默认 7s）经常等不到计划。</li>
     * </ul>
     */
    public ChatClient.ChatOptions checkpointOptions(String effectiveLevel) {
        if ("off".equals(effectiveLevel)) {
            // 顾问模式四段 + 方案比较合计约 900–1100 字，额度给足避免截成半截 JSON；路径模式 JSON 更紧凑
            return ChatClient.ChatOptions.fastPlan(pathPlanning ? 1_200 : 2_600,
                    Duration.ofMillis(plannerCallTimeoutMs));
        }
        long budget = Math.min(plannerCallTimeoutMs, 20_000);
        ChatClient.ChatOptions options = ChatClient.ChatOptions.deepPlan(8_000, Duration.ofMillis(budget));
        // low 与推理档默认相同（no-op）；medium/high 在这里生效
        return effectiveLevel == null ? options : options.withReasoning(effectiveLevel);
    }
}
