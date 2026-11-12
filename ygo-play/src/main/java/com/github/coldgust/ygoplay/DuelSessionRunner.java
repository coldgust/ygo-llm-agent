package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.selection.SelectionDescriber;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygoagent.selection.FallbackSelections;
import com.github.coldgust.ygoagent.selection.SelectionMapperImpl;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.DecisionEngine;
import com.github.coldgust.ygoagent.selection.SelectionMapper;
import com.github.coldgust.ygoagent.pipeline.reflex.FallbackIntents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;

public final class DuelSessionRunner {

    private static final Logger log = LoggerFactory.getLogger(DuelSessionRunner.class);

    private final SessionLink link;
    private final DecisionEngine port;
    private final SelectionMapper mapper;
    private final DecisionPointFactory factory;
    private final RunnerConfig config;
    private final ArrayDeque<String> recentSubmits = new ArrayDeque<>();
    private static final int REPEAT_WINDOW = 6;
    private static final int REPEAT_LIMIT = 3;

    private final SubmittedListener submitted;
    private final SelectionDescriber describer;

    public record Submission(DecisionPoint point, DuelDecision decision, ChoiceSelection selection,
                             String summary, boolean fallback) {
    }

    public interface SubmittedListener {
        void onSubmit(Submission submission);
    }

    public DuelSessionRunner(SessionLink link, DecisionEngine port, RunnerConfig config) {
        this(link, port, config, null);
    }

    public DuelSessionRunner(SessionLink link, DecisionEngine port, RunnerConfig config,
                             SubmittedListener submitted) {
        this(link, port, config, submitted, null);
    }

    public DuelSessionRunner(SessionLink link, DecisionEngine port, RunnerConfig config,
                             SubmittedListener submitted,
                             SelectionDescriber describer) {
        this.link = link;
        this.port = port;
        this.mapper = new SelectionMapperImpl();
        this.factory = new DecisionPointFactory();
        this.config = config == null ? RunnerConfig.defaults() : config;
        this.submitted = submitted;
        this.describer = describer == null
                ? new SelectionDescriber()
                : describer;
    }

    public RunStats run() throws InterruptedException {
        int answered = 0;
        int fallbacks = 0;
        while (!link.closed() && !link.finished()) {
            ChoiceRequest request = link.poll();
            if (request == null) {
                Thread.sleep(config.pollMillis());
                continue;
            }
            DecisionPoint point;
            try {
                point = factory.create(request, link.state());
            } catch (RuntimeException e) {
                log.warn("决策点构建失败，直接保底: {}", e.toString());
                fallbacks++;
                ChoiceSelection fallbackSelection = FallbackSelections.firstLegal(request);
                notifySubmitted(null, null, request, fallbackSelection, true);
                if (respond(request, fallbackSelection)) {
                    answered++;
                }
                continue;
            }

            DuelDecision decision;
            try {
                decision = port.decide(point, DecisionDeadline.of(config.budgetMillis(point.kind())));
            } catch (RuntimeException e) {
                log.warn("决策失败，走保底: {}", e.toString());
                decision = fallback(point, e);
                fallbacks++;
            }

            ChoiceSelection selection;
            boolean mappedFallback = false;
            try {
                selection = mapper.map(point, decision.intent());
            } catch (RuntimeException e) {
                // 这类兜底会把"想发动"悄悄变成"弃权"，必须带足上下文，否则永远发现不了
                log.warn("映射失败，走保底选择（{} / {}）: {}", point.kind(), decision.intent(), e.toString());
                fallbacks++;
                selection = FallbackSelections.firstLegal(request);
                mappedFallback = true;
            }
            selection = breakRepeatLoop(point, request, selection);
            notifySubmitted(point, decision, request, selection, mappedFallback);

            if (respond(request, selection)) {
                answered++;
            } else if (link.closed()) {
                break;
            }
        }
        return new RunStats(answered, fallbacks);
    }

    private ChoiceSelection breakRepeatLoop(DecisionPoint point, ChoiceRequest request,
                                            ChoiceSelection selection) {
        String key = point.kind() + "|" + describe(selection);
        recentSubmits.addLast(key);
        while (recentSubmits.size() > REPEAT_WINDOW) {
            recentSubmits.removeFirst();
        }
        long same = recentSubmits.stream().filter(key::equals).count();
        if (same < REPEAT_LIMIT) {
            return selection;
        }
        recentSubmits.clear();

        ChoiceSelection forced = null;
        if (request instanceof ChoiceRequest.UnselectRequest u) {
            ChoiceSelection.UnselectStep step = u.selectable().stream()
                    .filter(c -> !u.selected().contains(c))
                    .findFirst()
                    .map(c -> (ChoiceSelection.UnselectStep)
                            new ChoiceSelection.UnselectStep.Select(c))
                    .orElse(u.finishable() ? ChoiceSelection.UnselectStep.Finish.INSTANCE : null);
            if (step != null) {
                forced = new ChoiceSelection.UnselectSelection(step);
            }
        } else if (request instanceof ChoiceRequest.MainPhaseRequest m && m.canEndTurn()) {
            // 主阶段反复做同一个无效动作：直接结束回合，别烧时间
            forced = new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE);
        } else if (request instanceof ChoiceRequest.BattlePhaseRequest b && b.canEndBattlePhase()) {
            forced = new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EndBattlePhase.INSTANCE);
        }
        if (forced == null) {
            return selection;
        }
        log.warn("检测到原地打转（最近 {} 次里同一提交出现 {} 次：{}），强制改为 {}",
                REPEAT_WINDOW, REPEAT_LIMIT, describe(selection), describe(forced));
        return forced;
    }

    private String describe(ChoiceSelection selection) {
        try {
            return describer.describe(null, selection);
        } catch (RuntimeException e) {
            return String.valueOf(selection);
        }
    }

    private void notifySubmitted(DecisionPoint point, DuelDecision decision,
                                 ChoiceRequest request, ChoiceSelection selection, boolean fallback) {
        if (submitted == null) {
            return;
        }
        String summary;
        try {
            summary = describer.describe(request, selection);
        } catch (RuntimeException e) {
            summary = String.valueOf(selection);
        }
        try {
            submitted.onSubmit(new Submission(point, decision, selection, summary, fallback));
        } catch (RuntimeException e) {
            log.debug("提交观察者异常（忽略）: {}", e.toString());
        }
    }

    private boolean respond(ChoiceRequest request, ChoiceSelection selection) {
        try {
            link.respond(selection);
            return true;
        } catch (Exception e) {
            log.error("应答失败（{}）: {}", request.getClass().getSimpleName(), e.toString());
            return false;
        }
    }

    private static DuelDecision fallback(DecisionPoint point, RuntimeException cause) {
        return new DuelDecision(
                FallbackIntents.first(point),
                PathKind.FALLBACK,
                0.0,
                DecisionExplanation.of("异常保底: " + cause.getClass().getSimpleName()),
                Timing.ZERO);
    }
}
