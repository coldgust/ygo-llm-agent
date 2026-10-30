package com.github.coldgust.ygoduellog.model;

import java.util.List;

/**
 * 决策 episode：一个决策点的可见局面 + 候选 + 选择。
 *
 * <p>现在的用途只有一个：对局复盘时从对局日志里按回合切出"玩家实际打出的动作序列"。
 * 它不再是"对局库检索单元"，也不再承担 combo 录入（录入已改为直接记录操作存 PG）。
 *
 * @param duelId         对局 id（文件名）
 * @param requestSeq     DECISION_REQUESTED 的 seq
 * @param turn           回合
 * @param phase          阶段
 * @param requestType    请求类型
 * @param candidateCount 候选数（DECISION_REQUESTED 自带；有 CONTEXT 时取 CONTEXT）
 * @param candidates     候选快照（旧日志可能为空）
 * @param chosen         选择摘要
 * @param source         PLAYER / FALLBACK / TIMEOUT_FALLBACK / SKIPPED
 * @param thinkMs        思考耗时
 * @param answered       是否已作答
 * @param handPasscodes  当时我方手牌卡号（可见）
 * @param selfLp         我方 LP
 * @param oppLp          对方 LP
 * @param fieldCards     我方场上卡号
 */
public record DecisionEpisode(
        String duelId,
        long requestSeq,
        int turn,
        String phase,
        String requestType,
        int candidateCount,
        List<CandidateView> candidates,
        String chosen,
        String source,
        long thinkMs,
        boolean answered,
        List<Long> handPasscodes,
        int selfLp,
        int oppLp,
        List<Long> fieldCards
) {

    public DecisionEpisode {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        handPasscodes = handPasscodes == null ? List.of() : List.copyOf(handPasscodes);
        fieldCards = fieldCards == null ? List.of() : List.copyOf(fieldCards);
    }
}
