package com.github.coldgust.ygoplay;

import com.github.coldgust.ygoagent.decision.map.SelectionDescriber;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygoagent.decision.map.FallbackSelections;
import com.github.coldgust.ygoagent.decision.map.SelectionMapperImpl;
import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionExplanation;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.PathKind;
import com.github.coldgust.ygoagent.decision.model.Timing;
import com.github.coldgust.ygoagent.decision.port.DecisionPort;
import com.github.coldgust.ygoagent.decision.port.SelectionMapper;
import com.github.coldgust.ygoagent.decision.reflex.FallbackIntents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;

/**
 * 实时决策循环：poll → 建决策点 → 决策 → 映射 → 应答。
 * <p>任何异常都降级到保底选择，绝不让循环卡死。
 */
public final class DuelSessionRunner {

    private static final Logger log = LoggerFactory.getLogger(DuelSessionRunner.class);

    private final SessionLink link;
    private final DecisionPort port;
    private final SelectionMapper mapper;
    private final DecisionPointFactory factory;
    private final RunnerConfig config;
    /**
     * 死循环熔断：同一请求类型 + 同一提交内容连续出现 {@link #REPEAT_LIMIT} 次，
     * 说明这一步既不推进也不改变局面（典型：特招→选素材→取消→再特招）。
     * 实机曾这样空转 127 次，把整局拖死。
     */
    private final ArrayDeque<String> recentSubmits = new ArrayDeque<>();
    /** 观察窗口：只在最近若干次提交里判重，避免被久远的同类动作影响。 */
    private static final int REPEAT_WINDOW = 6;
    /** 同一提交在窗口内出现这么多次，就认定在原地打转。 */
    private static final int REPEAT_LIMIT = 3;

    /** 提交观察者：把"最终提交了什么"送进轨迹/前端（token = null 表示不关心）。 */
    private final SubmittedListener submitted;
    private final SelectionDescriber describer;

    /** 一次成功提交的摘要（供轨迹与界面展示）。 */
    public record Submission(DecisionPoint point, DuelDecision decision, ChoiceSelection selection,
                             String summary, boolean fallback) {
    }

    public interface SubmittedListener {
        void onSubmit(Submission submission);
    }

    public DuelSessionRunner(SessionLink link, DecisionPort port, RunnerConfig config) {
        this(link, port, config, null);
    }

    public DuelSessionRunner(SessionLink link, DecisionPort port, RunnerConfig config,
                             SubmittedListener submitted) {
        this(link, port, config, submitted, null);
    }

    /**
     * @param describer 卡名解析器用于把卡号翻成卡名；null 时只显示卡号
     */
    public DuelSessionRunner(SessionLink link, DecisionPort port, RunnerConfig config,
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

    /**
     * 同一动作反复提交却不推进 → 强制换一个能推进的选择。
     *
     * <p>为什么放在运行器而不是决策层：任何一层都可能产生"看似合理、实际原地打转"的循环
     * （模型取消、经验规则反复选同一个、映射兜底…）。这里是所有应答的唯一出口，兜得住。
     */
    private ChoiceSelection breakRepeatLoop(DecisionPoint point, ChoiceRequest request,
                                            ChoiceSelection selection) {
        // 用"最近 N 次提交"判重，而不是"连续相同"：真实的死循环是两个动作交替
        // （主阶段特招 ↔ 取消选择），连续判重永远命中不了。
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

    /** 把"最终提交的操作"发出去：决策路径再清楚，不看提交内容也无法复盘。 */
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
