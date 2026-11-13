package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.DuelDecision;

/**
 * ②③ 的命中结果：决策本体 + 结构化的命中步号（前端 payload 的 planStep 直接取这里，
 * 不再从 evidence 字符串反解析）。evidence 文本仍是给人看的结论，两者并行。
 *
 * @param decision  命中的决策
 * @param stepIndex 命中的计划步号（0 起，与 {@code PlanStep#index()} 一致）
 * @param fromBranch true = ③ 分支预案命中，false = ② 主线精确命中
 */
public record PlanHit(DuelDecision decision, int stepIndex, boolean fromBranch) {
}
