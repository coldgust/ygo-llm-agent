package com.github.coldgust.ygoduellog.query;

/**
 * 一次完整决策（DECISION_REQUESTED 与 MADE/SKIPPED 的配对展平）。
 *
 * @param requestSeq       DECISION_REQUESTED 事件的 seq
 * @param answeredSeq      应答事件的 seq（SKIPPED 时也有）
 * @param player           "P0"/"P1"
 * @param requestType      请求类型
 * @param turn             决策所在回合
 * @param requestSummary   请求摘要（候选与约束）
 * @param outcome          MADE / SKIPPED
 * @param selectionSummary 决策摘要（SKIPPED 时为跳过原因）
 * @param source           来源：PLAYER / FALLBACK / TIMEOUT_FALLBACK / null（SKIPPED）
 * @param thinkMs          思考耗时
 * @param retryCount       提交前累计 RETRY 次数
 */
public record DecisionRecord(
        long requestSeq,
        long answeredSeq,
        String player,
        String requestType,
        int turn,
        String requestSummary,
        String outcome,
        String selectionSummary,
        String source,
        long thinkMs,
        int retryCount
) {
}
