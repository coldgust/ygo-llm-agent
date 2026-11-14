package com.github.coldgust.ygoagent.pipeline.advisor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规划器（战术幕僚）最新建议快照的持有者（docs/planner-judge-memory-redesign.md §6.2）。
 * 替代路径规划的 {@code PlanKeeper}：没有 cursor/grounding/carry-over——
 * keep = 快照原样保留（渲染层按回合号标"上回合制定，仅供参考"）；新建议 = 整体替换。
 *
 * <p>无锁读语义（决策线程随时 {@link #current()}）；只在 planner 单线程（worker）上写入。
 */
public final class AdvisorKeeper {

    /** 已发布建议：内容 + 标识 + 发布回合（渲染层用它判断 stale）。 */
    public record Snapshot(AdvisorAnswer answer, String adviceId, int turn, double confidence) {
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public Optional<Snapshot> current() {
        return Optional.ofNullable(current.get());
    }

    public boolean isEmpty() {
        return current.get() == null;
    }

    /** 发布新建议（锚点产出或修订更新；整体替换）。 */
    public void publish(AdvisorAnswer answer, String adviceId, int turn) {
        current.set(new Snapshot(answer, adviceId, turn, answer.confidenceOr(0.5)));
    }
}
