package com.github.coldgust.ygoagent.pipeline.advisor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规划器（战术幕僚）最新建议快照的持有者（docs/planner-judge-memory-redesign.md §6.2）。
 * 替代路径规划的 {@code PlanKeeper}：没有 cursor/grounding/carry-over——
 * 新建议整体替换；决策器判决重规划时 {@link #retire()} 立即作废
 * （新建议发布前 {@link #current()} 为空，决策器不带旧规划）。
 * keep 已废止（2026-11-14 用户指令）：每回合锚点规划一次 + 决策器判决重规划，
 * 每次 checkpoint 都必须产出全量建议。
 *
 * <p>无锁读语义（决策线程随时 {@link #current()}）；写入发生在 planner worker 与
 * 决策线程（retire，AtomicReference 写，线程安全）。
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

    /** 发布新建议（锚点产出或重规划更新；整体替换）。 */
    public void publish(AdvisorAnswer answer, String adviceId, int turn) {
        current.set(new Snapshot(answer, adviceId, turn, answer.confidenceOr(0.5)));
    }

    /**
     * 作废当前建议（决策器判决 needReplan=true 时立即调用）：
     * 决策器已判定旧规划不可用——重规划没来得及发布前，{@link #current()} 为空，
     * 决策器的快照渲染自然不带 [规划器情报] 段（不带上旧规划内容）。
     * 只在确认会发起重规划后调用；可从决策线程直接调（AtomicReference 写）。
     */
    public void retire() {
        current.set(null);
    }
}
