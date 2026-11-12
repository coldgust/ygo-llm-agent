package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TurnMemory;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygoagent.llm.VerboseLog;

import java.util.Locale;
import java.util.Optional;

/**
 * ② 照计划 + ③ 走预案：把规划器的回合计划接到当前决策点的真实候选上。
 * <p>② 沿计划主线找下一个能接地的步；③ 主线接不上时给分支预案一次机会（高 stakes 时由编排方跳过）。
 * 接地失败不做改判——是否作废计划、何时进 ④，由 {@code DecisionPipeline} 编排决定。
 */
public final class PlanFollower {

    private final PlannerSession planner;
    private final StepGrounder grounder;
    private final SimilarityMatcher similarity;
    private final PipelineSettings settings;
    private final TurnMemory memory;
    private final VerboseLog trace;

    public PlanFollower(PlannerSession planner, StepGrounder grounder, SimilarityMatcher similarity,
                        PipelineSettings settings, TurnMemory memory, VerboseLog trace) {
        this.planner = planner;
        this.grounder = grounder;
        this.similarity = similarity;
        this.settings = settings;
        this.memory = memory;
        this.trace = trace;
    }

    /** ② 计划精确匹配：从游标向后找第一个匹配本请求且能接地的步。 */
    public Optional<DuelDecision> tryExact(DecisionPoint point, TurnPlan plan) {
        if (!plan.active()) {
            return Optional.empty();
        }
        for (int i = plan.cursor(); i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            if (step.expectedRequest() != point.kind()) {
                continue;
            }
            Optional<DuelDecision> decision = buildFromStep(point, step, PathKind.PLAN_STEP,
                    "计划步#" + step.index() + " " + explainStep(step)
                            + (i > plan.cursor() ? "（跳过 " + i + " 步不可接地的前置步）" : ""));
            if (decision.isPresent()) {
                planner.advanceCursorTo(i + 1);
                return decision;
            }
        }
        return Optional.empty();
    }

    /** ③ 分支相似命中 → 分支首步（接不地 = 假命中，§2.2.4）。 */
    public Optional<DuelDecision> trySimilar(DecisionPoint point, TurnPlan plan) {
        Optional<SimilarityMatcher.Match> match =
                similarity.bestBranch(point, plan, settings.similarityThreshold());
        if (match.isEmpty() || match.get().branch().steps().isEmpty()) {
            return Optional.empty();
        }
        PlanStep branchStep = match.get().branch().steps().getFirst();
        return buildFromStep(point, branchStep, PathKind.PLAN_BRANCH,
                "分支[" + match.get().branch().name() + "] 相似度 "
                        + String.format(Locale.ROOT, "%.2f", match.get().score()));
    }

    private Optional<DuelDecision> buildFromStep(DecisionPoint point, PlanStep step,
                                                 PathKind kind, String evidence) {
        Optional<ChoiceIntent> intent = grounder.toIntent(point, step);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        String action = step.alternatives().isEmpty() ? "?" : step.alternatives().getFirst().verb()
                + "(passcode=" + step.alternatives().getFirst().passcode() + ")";
        trace.decision(kind == PathKind.PLAN_STEP ? "② 照计划" : "③ 走预案",
                "第 " + (step.index() + 1) + " 步 " + action + " ｜ " + evidence);
        // 计划步的位置随这次决策一起交出去（若这一步会落场，紧随其后的位置请求就用它）
        memory.recordDecisionZone(step.alternatives().isEmpty() ? null : step.alternatives().getFirst().zone());
        return Optional.of(new DuelDecision(intent.get(), kind,
                Math.max(0.5, step.confidence()),
                DecisionExplanation.of(evidence), Timing.ZERO));
    }

    private static String explainStep(PlanStep step) {
        Object why = step.expectedEffects().get("why");
        if (why != null) {
            return String.valueOf(why);
        }
        return step.alternatives().isEmpty() ? "" : step.alternatives().getFirst().verb();
    }
}
