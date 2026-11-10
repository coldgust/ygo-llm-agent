package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionDeadline;
import com.github.coldgust.ygoagent.decision.model.DecisionExplanation;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.DuelDecision;
import com.github.coldgust.ygoagent.decision.model.PathKind;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.model.Timing;
import com.github.coldgust.ygoagent.decision.plan.CandidateIntents;
import com.github.coldgust.ygoagent.decision.plan.PlanStep;
import com.github.coldgust.ygoagent.decision.plan.ZoneCodes;
import com.github.coldgust.ygoagent.decision.planexec.prompt.DecisionMemory;
import com.github.coldgust.ygoagent.decision.planexec.prompt.IntentSummary;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTrace;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTuning;
import com.github.coldgust.ygoagent.decision.planexec.llm.ComboTool;
import com.github.coldgust.ygoagent.decision.planexec.llm.RulebookTool;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlannerPrompts;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlannerSession;
import com.github.coldgust.ygoagent.decision.planexec.planner.TurnPlan;
import com.github.coldgust.ygoagent.decision.port.DecisionPort;
import com.github.coldgust.ygoagent.decision.port.DecisionTraceWriter;
import com.github.coldgust.ygoagent.decision.port.ReflexPolicy;
import com.github.coldgust.ygoagent.decision.reflex.FallbackIntents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * v6 执行器（§2.2）：① Reflex → ② 计划精确匹配 → ③ 相似匹配 → ④ 投机双路 → ⑤ 保底。
 * <p>fail-closed：任何一级失败静默落到下一级；任何异常落到 ⑤（delegate）。
 * 硬边界（§2.2.10）：确定性部件只回答"计划能否兑现"，不回答"该怎么打"。
 */
public final class PlanExecutorDecisionPort implements DecisionPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutorDecisionPort.class);

    private final ReflexPolicy reflex;
    private final IntentSummary intentSummary;

    /**
     * 正在结算的效果（卡号 + 本次发动的那一条描述码），只在本回合内有效。
     */
    private volatile int pendingEffectTurn = -1;
    private volatile long pendingEffectCard;
    private volatile long pendingEffectDesc;

    private final List<String> turnActions = new ArrayList<>();
    private volatile int turnActionsTurn = -1;
    private volatile String lastIntent = "";
    private final PlannerSession planner;
    private final ContextPackBuilder packer;
    private final ChatClient chat;
    private final DecisionTools tools;
    private final PlannerSettings settings;
    private final CardFacts facts;
    private final DecisionPort delegate;
    private final DecisionTraceWriter traceWriter;
    private final GroundingEngine grounding = new GroundingEngine();
    private final Set<Integer> turnFirstOneshotSeen =
            ConcurrentHashMap.newKeySet();
    /**
     * 位置线索：模型在"选动作"的同一次回答里一并给出（计划步的 {@code zone}，或 one-shot 的 {@code zone}），
     * 紧随其后的位置请求直接兑现它；没给/给得不合法就退到"第一个可放置"。
     * <p>带回合号：跨回合的旧线索必须作废，否则会把上回合想放的格子套到这回合的召唤上。
     */
    private volatile ZoneHint pendingZone;

    private record ZoneHint(int turn, ZoneRef zone) {
    }

    /** 仅由决策线程（buildFromStep 等）写入，供同一次 decide() 采集"这次选的动作要放哪"。 */
    private ZoneRef lastDecisionZone;
    private final SimilarityMatcher similarity = new SimilarityMatcher();
    private final ExecutorService oneshotPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ygo-oneshot");
        t.setDaemon(true);
        return t;
    });

    public PlanExecutorDecisionPort(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, ChatClient chat,
                                    PlannerSettings settings, CardFacts facts,
                                    DecisionPort delegate, DecisionTraceWriter traceWriter) {
        this(reflex, planner, packer, chat, settings, facts, delegate, traceWriter,
                DecisionTools.NONE);
    }

    public PlanExecutorDecisionPort(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, ChatClient chat,
                                    PlannerSettings settings, CardFacts facts,
                                    DecisionPort delegate, DecisionTraceWriter traceWriter,
                                    RulebookTool rulebook) {
        this(reflex, planner, packer, chat, settings, facts, delegate, traceWriter,
                rulebook, null);
    }

    public PlanExecutorDecisionPort(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, ChatClient chat,
                                    PlannerSettings settings, CardFacts facts,
                                    DecisionPort delegate, DecisionTraceWriter traceWriter,
                                    RulebookTool rulebook, ComboTool combo) {
        this(reflex, planner, packer, chat, settings, facts, delegate, traceWriter,
                new DecisionTools(rulebook, combo, null));
    }

    public PlanExecutorDecisionPort(ReflexPolicy reflex, PlannerSession planner,
                                    ContextPackBuilder packer, ChatClient chat,
                                    PlannerSettings settings, CardFacts facts,
                                    DecisionPort delegate, DecisionTraceWriter traceWriter,
                                    DecisionTools tools) {
        this.reflex = reflex;
        this.planner = planner;
        this.packer = packer;
        this.chat = chat;
        this.settings = settings;
        this.facts = facts;
        this.intentSummary = new IntentSummary(facts);
        this.delegate = delegate;
        this.traceWriter = traceWriter == null ? DecisionTraceWriter.NOOP : traceWriter;
        this.tools = tools == null ? DecisionTools.NONE : tools;
    }

    @Override
    public DuelDecision decide(DecisionPoint point, DecisionDeadline deadline) {
        long start = System.nanoTime();
        try {
            planner.observePoint(point);
        } catch (RuntimeException ignored) {
            // 规划器故障不影响决策
        }
        DuelDecision decision;
        String level;
        String levelLabel;
        List<String> missReasons = new ArrayList<>();
        try {
            decision = decideInner(point, deadline, missReasons);
            level = decision.pathKind().name();
            levelLabel = decision.pathKind().label();
        } catch (RuntimeException e) {
            log.warn("执行器异常，走 ⑤ 保底: {}", e.toString());
            decision = new DuelDecision(FallbackIntents.first(point), PathKind.FALLBACK, 0.0,
                    DecisionExplanation.of("执行器异常降级: " + e.getClass().getSimpleName()), Timing.ZERO);
            level = "EXCEPTION";
            levelLabel = "异常降级";
        }
        rememberEffectScope(point, decision);
        recordTurnAction(point, decision);
        try {
            planner.observeAnswered(point, decision.intent());
            if (point.kind() == RequestKind.MAIN_PHASE || point.kind() == RequestKind.BATTLE_PHASE) {
                planner.flushEvents();
            }
        } catch (RuntimeException ignored) {
        }
        long end = System.nanoTime();
        // 全链路日志：一行看清"这次是谁做的决定、花了多久、为什么没走上一级"
        LlmTrace.decision("seq=" + point.seq() + " T" + point.turn() + " " + point.kind().label()
                        + " → " + levelLabel + "（" + level + "）",
                "conf=" + String.format(Locale.ROOT, "%.2f", decision.confidence())
                        + " " + (end - start) / 1_000_000 + "ms"
                        + (missReasons.isEmpty() ? "" : " ｜ 未命中: " + String.join(" | ", missReasons))
                        + " ｜ 提交: " + LlmTrace.preview(decision.explanation().conclusion(), 80));
        // 前端要画"计划 → 决策"的因果与路径，所以把计划进度、命中步、解释一起发出去
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("levelLabel", levelLabel);
        payload.put("requestLabel", point.kind().label());
        payload.put("intent", decision.intent().getClass().getSimpleName());
        payload.put("intentLabel", ChoiceIntent.label(decision.intent()));
        payload.put("confidence", decision.confidence());
        payload.put("missReasons", String.join(" | ", missReasons));
        String conclusion = decision.explanation().conclusion();
        payload.put("explanation", conclusion == null ? "" : conclusion);
        TurnPlan plan = planner.currentPlan().orElse(null);
        if (plan != null) {
            payload.put("planId", plan.planId());
            payload.put("planCursor", plan.cursor());
            payload.put("planSteps", plan.steps().size());
            payload.put("planGoal", plan.goal());
        }
        Integer hitStep = planStepOf(conclusion);
        if (hitStep != null) {
            payload.put("planStep", hitStep);
        }
        event(point, "EXEC_DECISION", payload);
        // 位置线索：本次选了会落场的动作 → 记住模型一并给出的格子；本次就是位置请求 → 用完清掉
        rememberPlacement(point, decision);
        DuelDecision finalDecision = decision.withTiming(new Timing(0, end - start, end - start));
        try {
            traceWriter.append(point, finalDecision);
        } catch (RuntimeException ignored) {
        }
        return finalDecision;
    }

    /**
     * 记录/消费位置线索。
     * <ul>
     *   <li>本次请求是 PLACE → 线索已消费，清空；</li>
     *   <li>本次选了会落场的动作（召唤/特召/盖放/发动）且模型给了 zone → 记下（带回合号）；</li>
     *   <li>其他情况保持原状（召唤之后可能先问表示形式、再问位置）。</li>
     * </ul>
     * 没给 zone 时不保留旧值：宁可退到"第一个可放置"，也不要把上一张卡的格子套到这张卡上。
     */
    private void rememberPlacement(DecisionPoint point, DuelDecision decision) {
        if (point.kind() == RequestKind.PLACE) {
            pendingZone = null;
            return;
        }
        if (!placesOnField(decision.intent())) {
            return;
        }
        pendingZone = lastDecisionZone == null ? null : new ZoneHint(point.turn(), lastDecisionZone);
        lastDecisionZone = null;
        if (pendingZone != null) {
            // 让复盘能看出"这次动作模型打算放哪"，以及后面到底用没用上
            event(point, "POSITION_HINT", Map.of("zone", ZoneCodes.code(pendingZone.zone())));
        }
    }

    private static boolean placesOnField(ChoiceIntent intent) {
        return intent instanceof ChoiceIntent.Summon
                || intent instanceof ChoiceIntent.SpecialSummon
                || intent instanceof ChoiceIntent.SetMonster
                || intent instanceof ChoiceIntent.SetSpellTrap
                || intent instanceof ChoiceIntent.Activate;
    }

    /**
     * 位置兑现（在 ① REFLEX 之前）：把模型在<b>选动作时一并给出</b>的位置落到这次 PLACE 请求上。
     *
     * <p>优先级：one-shot/计划接地时模型当场给的 zone（最新）→ 计划当前步的 zone（早先规划）→ 空。
     * 空则落回 ① REFLEX 的"取第一个可放置区域"——<b>不额外发一次请求去问位置</b>。
     * 模型给的格子不在本次可放置范围内（被占/规则不允许）时同样视为没给。
     */
    private Optional<DuelDecision> plannedPlacement(DecisionPoint point) {
        List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        ZoneHint hint = pendingZone;
        pendingZone = null; // 一次线索只兑现一次
        if (hint != null && hint.turn() == point.turn()) {
            Optional<Candidate.ZoneC> hit = zones.stream()
                    .filter(z -> hint.zone().equals(z.value()))
                    .findFirst();
            if (hit.isPresent()) {
                return Optional.of(new DuelDecision(
                        new ChoiceIntent.Place(List.of(hit.get().id())), PathKind.PLAN_STEP, 0.8,
                        DecisionExplanation.of("模型给出的位置：" + ZoneCodes.describe(hint.zone())),
                        Timing.ZERO));
            }
        }
        return planPlacement(point, zones);
    }

    /**
     * 计划里的位置：写在**动作那一步**（{@code summon 零衣 zone=MMZ-3}），
     * 引擎却把"放哪格"拆成紧随其后的独立请求 → 回看刚执行过的一两步。
     * 只回看两步：再往前就是别的动作了，套错格子比不套更糟。
     */
    private Optional<DuelDecision> planPlacement(DecisionPoint point, List<Candidate.ZoneC> zones) {
        TurnPlan plan = planner.currentPlan().filter(p -> p.turnNumber() == point.turn()).orElse(null);
        if (plan == null || plan.steps().isEmpty()) {
            return Optional.empty();
        }
        int lowest = Math.max(0, plan.cursor() - 2);
        for (int i = Math.min(plan.cursor(), plan.steps().size()) - 1; i >= lowest; i--) {
            PlanStep step = plan.steps().get(i);
            if (step.alternatives().isEmpty()) {
                continue;
            }
            ZoneRef zone = step.alternatives().getFirst().zone();
            if (zone == null) {
                continue;
            }
            Optional<Candidate.ZoneC> hit = zones.stream()
                    .filter(z -> zone.equals(z.value()))
                    .findFirst();
            if (hit.isEmpty()) {
                continue; // 计划要的格子不可放：不猜，交给第一个可放置
            }
            return Optional.of(new DuelDecision(
                    new ChoiceIntent.Place(List.of(hit.get().id())), PathKind.PLAN_STEP,
                    Math.max(0.6, step.confidence()),
                    DecisionExplanation.of("计划位置：第 " + (i + 1) + " 步指定 "
                            + ZoneCodes.describe(zone)), Timing.ZERO));
        }
        return Optional.empty();
    }


    /**
     * 记下"这一手发动的是哪张卡的哪一条效果"，供随后的从属决策点（选卡/选位置/选表示形式）使用。
     *
     * <p>只在<b>发起效果</b>的三类意图上记录；连锁弃权、主阶段结束这类不算（清了反而更准）。
     */
    private void rememberEffectScope(DecisionPoint point, DuelDecision decision) {
        Object intent = decision.intent();
        CandidateId effectId = null;
        if (intent instanceof ChoiceIntent.ChainRespond r) {
            effectId = r.option();
        } else if (intent instanceof ChoiceIntent.Activate a) {
            effectId = a.effect();
        }
        if (effectId == null
                || (point.kind() != RequestKind.CHAIN
                && point.kind() != RequestKind.MAIN_PHASE
                && point.kind() != RequestKind.BATTLE_PHASE
                && point.kind() != RequestKind.EFFECT_YES_NO)) {
            return;
        }
        CandidateId id = effectId;
        point.candidatesOf(Candidate.EffectC.class).stream()
                .filter(c -> c.id().value().equals(id.value()))
                .findFirst()
                .ifPresent(c -> {
                    pendingEffectTurn = point.turn();
                    pendingEffectCard = c.value().card().identity().passcode();
                    pendingEffectDesc = c.value().description();
                });
    }

    /** 本回合动作记录上限：再多也只是噪声。 */
    private static final int TURN_ACTION_LIMIT = 8;

    /**
     * 记一笔"我刚做过什么、刚才是怎么想的"。
     */
    private void recordTurnAction(DecisionPoint point, DuelDecision decision) {
        if (point.turn() != turnActionsTurn) {
            turnActions.clear();
            turnActionsTurn = point.turn();
            lastIntent = "";
        }
        lastIntent = LlmTrace.preview(decision.explanation() == null
                ? "" : decision.explanation().conclusion(), 80);
        String line = intentSummary.of(decision.intent(), point);
        if (line == null || line.isBlank()) {
            return;
        }
        if (!turnActions.isEmpty()) {
            String last = turnActions.get(turnActions.size() - 1);
            int mark = last.lastIndexOf(" ×");
            String base = mark > 0 ? last.substring(0, mark) : last;
            if (base.equals(line)) {
                int times = mark > 0 ? Integer.parseInt(last.substring(mark + 2)) : 1;
                turnActions.set(turnActions.size() - 1, base + " ×" + (times + 1));
                return;
            }
        }
        turnActions.add(line);
        while (turnActions.size() > TURN_ACTION_LIMIT) {
            turnActions.remove(0);
        }
    }

    private DuelDecision decideInner(DecisionPoint point, DecisionDeadline deadline, List<String> missReasons) {
        // ⓪ 位置兑现：模型在选动作时已一并给出位置 → 直接用，不再额外问一次
        if (point.kind() == RequestKind.PLACE) {
            Optional<DuelDecision> placed = plannedPlacement(point);
            if (placed.isPresent()) {
                LlmTrace.decision("⓪ 位置兑现", placed.get().explanation().conclusion());
                return placed.get();
            }
        }
        // ① Reflex（机械请求 + 已知表格策略）
        Optional<ChoiceIntent> quick = reflex.tryAnswer(point);
        if (quick.isPresent()) {
            LlmTrace.decision("① 唯一合法", quick.get().getClass().getSimpleName() + "（没有判断余地）");
            return new DuelDecision(quick.get(), PathKind.ONLY_CHOICE, 1.0,
                    DecisionExplanation.of("规则直觉命中"), Timing.ZERO);
        }
        if (!point.candidates().isEmpty()) {
            Optional<TurnPlan> planOpt = planner.currentPlan()
                    .filter(p -> p.turnNumber() == point.turn());
            if (planOpt.isEmpty()) {
                long waitMs = planWaitMs(turnFirstOneshotSeen.contains(point.turn()),
                        settings, planner.remainingTurnBudget());
                if (waitMs > 0 && planner.healthy()) {
                    turnFirstOneshotSeen.add(point.turn());
                    long before = System.currentTimeMillis();
                    planOpt = planner.waitForPlan(point.turn(), waitMs);
                    long waited = System.currentTimeMillis() - before;
                    deadline = shiftedBy(deadline, waited); // 等待不计入决策预算
                    log.info("本回合首次决策器判断（{}）：等计划 {}ms，实际 {}ms，决策预算已顺延",
                            point.kind(), waitMs, waited);
                }
            }
            TurnPlan plan = planOpt.orElse(null);
            if (plan != null) {
                // ② 计划精确匹配
                Optional<DuelDecision> exact = tryExact(point, plan);
                if (exact.isPresent()) {
                    return exact.get();
                }
                if (plan.steps().isEmpty()) {
                    // 骨架计划：不接地、不重复作废，直接进 ④，
                    // 但计划上下文仍会把它带给 one-shot，避免决策与战术思想背道而驰。
                    missReasons.add(settings.pathPlanning()
                            ? "计划路径已作废，仅保留战术思想（改决策器判断）"
                            : "顾问模式：无固定路径，按战术建议决策器判断");
                } else if (!plan.active()) {
                    missReasons.add("计划已走完（cursor=" + plan.cursor() + "/"
                            + plan.steps().size() + "）");
                } else if (subChoice(point.kind())) {
                    missReasons.add("本决策点是附属选择（" + point.kind().label()
                            + "），计划主线仍然有效，按决策器判断");
                } else {
                    missReasons.add("计划步未接地（cursor=" + plan.cursor() + "/"
                            + plan.steps().size() + "）");
                    // ③ 相似匹配（stakes 高时跳过，§2.2.4）：
                    // 分支是"另一条计划"，主线接不上时它仍可能成立，所以先给分支一次机会。
                    if (!highStakes(point)) {
                        Optional<DuelDecision> similar = trySimilar(point, plan);
                        if (similar.isPresent()) {
                            return similar.get();
                        }
                        missReasons.add("无分支相似命中（阈值 " + settings.similarityThreshold() + "）");
                    } else {
                        missReasons.add("相似匹配跳过（高 stakes）");
                    }
                    // ②③ 全落空 → 这份计划的前提已经不成立：整份作废，退回决策器判断。
                    abandonUngroundedPlan(plan, point, missReasons);
                    plan = null;
                }
            } else {
                missReasons.add("本回合计划尚未就绪（锚点竞速/规划器降级）");
            }


            // ④ 投机双路：不依赖计划存在（one-shot 是独立路径，实机 r2 的教训）
            long escalateStart = System.currentTimeMillis();
            Optional<DuelDecision> escalated = escalate(point, deadline, missReasons);
            if (escalated.isPresent()) {
                LlmTrace.step("④ 决策器判断", System.currentTimeMillis() - escalateStart,
                        escalated.get().pathKind().label() + " ｜ " + escalated.get().explanation().conclusion());
                return escalated.get();
            }
            missReasons.add("升级无有效结果（超时/校验失败）");
            log.debug("执行器降级到决策器判断/兜底: {}", missReasons);
        }
        // ⑤ 保底：原决策链路（Tactical/Arbiter/Fallback）
        DuelDecision fallback = delegate.decide(point, deadline);
        LlmTrace.decision("⑤ 兜底", fallback.pathKind().label() + " ｜ "
                + fallback.explanation().conclusion());
        return fallback;
    }

    /**
     * 本回合"等计划"的时长：整回合只有一次，时长统一，与请求类型/阶段无关。
     * <ul>
     *   <li>本回合已经等过 → 0；</li>
     *   <li>否则 → {@code firstOneshotWaitMs}（默认 7s），只受"本回合等待额度"约束；</li>
     *   <li>{@code waitPerDecisionMs=0} 表示显式关闭等待。</li>
     * </ul>
     * 等待不占用决策预算（调用方会把截止时间顺延），所以这里不需要为 one-shot 预留时间。
     */
    static long planWaitMs(boolean waitedThisTurn, PlannerSettings settings, long remainingTurnBudgetMs) {
        if (waitedThisTurn || settings.waitPerDecisionMs() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(settings.firstOneshotWaitMs(), remainingTurnBudgetMs));
    }

    /** 把截止时间整体后移（等待属于额外时间，不从决策预算里扣）。 */
    private static DecisionDeadline shiftedBy(DecisionDeadline deadline, long extraMillis) {
        return extraMillis <= 0 ? deadline
                : new DecisionDeadline(deadline.startNanos(),
                deadline.hardNanos() + extraMillis * 1_000_000L, deadline.safetyMillis());
    }

    /**
     * 计划接地失败 → 整份作废 + 请求重新规划。
     */
    private void abandonUngroundedPlan(TurnPlan plan, DecisionPoint point, List<String> missReasons) {
        int cursor = plan.cursor();
        PlanStep stuck = cursor < plan.steps().size() ? plan.steps().get(cursor) : null;
        String desc = stuck == null || stuck.alternatives().isEmpty() ? "?"
                : stuck.alternatives().getFirst().verb() + " "
                  + stuck.alternatives().getFirst().passcode();
        missReasons.add("计划作废：第 " + (cursor + 1) + " 步 [" + desc + "] 接不上地（"
                + cursor + "/" + plan.steps().size() + "），改决策器判断");
        event(point, "PLAN_ABANDONED", Map.of("planId", plan.planId(), "cursor", cursor,
                "steps", plan.steps().size(), "action", desc));
        log.info("计划接地失败（{}，第 {} 步 [{}]）→ 作废并请求重新规划", plan.planId(), cursor + 1, desc);
        try {
            planner.abandonAndReplan("第 " + (cursor + 1) + " 步 [" + desc + "] 在当前局面接不上地");
        } catch (RuntimeException ignored) {
            // 规划器故障不影响本决策：下面直接走 ④ 决策器判断
        }
    }

    /**
     * 计划主线之外的"派生请求"：位置、表示形式、选卡/祭品/排序/宣言，
     * 以及连锁与"是否发动"——这两类在计划里只是<b>预测</b>（"对手检索就丢灰"），
     * 预测没命中不等于计划破产。
     *
     * <p>它们接不上地时<b>只降级到决策器判断</b>，不作废、不重规划。
     * 只有主线动作（主阶段/战阶那几步）接不上地，才说明计划前提真的崩了。
     */
    private static boolean subChoice(RequestKind kind) {
        return switch (kind) {
            case PLACE, DISFIELD, POSITION, CARD_SELECT, TRIBUTE_SELECT, SUM, UNSELECT, SORT,
                 OPTION, DECLARE_RACE, DECLARE_ATTRIBUTE, DECLARE_CARD, DECLARE_NUMBER,
                 YES_NO, COUNTER, CHAIN, EFFECT_YES_NO -> true;
            default -> false;
        };
    }

    private Optional<DuelDecision> tryExact(DecisionPoint point, TurnPlan plan) {
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
    private Optional<DuelDecision> trySimilar(DecisionPoint point, TurnPlan plan) {
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
        Optional<ChoiceIntent> intent = grounding.toIntent(point, step);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        String action = step.alternatives().isEmpty() ? "?" : step.alternatives().getFirst().verb()
                + "(passcode=" + step.alternatives().getFirst().passcode() + ")";
        LlmTrace.decision(kind == PathKind.PLAN_STEP ? "② 照计划" : "③ 走预案",
                "第 " + (step.index() + 1) + " 步 " + action + " ｜ " + evidence);
        // 计划步的位置随这次决策一起交出去（若这一步会落场，紧随其后的位置请求就用它）
        lastDecisionZone = step.alternatives().isEmpty() ? null : step.alternatives().getFirst().zone();
        return Optional.of(new DuelDecision(intent.get(), kind,
                Math.max(0.5, step.confidence()),
                DecisionExplanation.of(evidence), Timing.ZERO));
    }

    private record Drafted(DuelDecision decision, ZoneRef zone) {
    }

    private DuelDecision accept(Drafted drafted) {
        lastDecisionZone = drafted.zone();
        return drafted.decision();
    }

    /**
     * ④ 投机双路：one-shot 立即 + 规划器紧急回复，先到先得；planner 信息全则优先（§2.2.5）。
     */
    private Optional<DuelDecision> escalate(DecisionPoint point, DecisionDeadline deadline,
                                            List<String> missReasons) {
        long deadlineMs = Math.max(0, deadline.remainingMillis() - deadline.safetyMillis());
        if (deadlineMs < 500) {
            missReasons.add("升级跳过：预算只剩 " + deadlineMs + "ms（<500ms）");
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        // ④ 的两路都在别的线程里跑，失败原因必须先收集再并回主线程（ArrayList 不是线程安全的）。
        // 细分成"调用失败 / 输出不可解析 / 选择越界 / 超时"，否则只剩一句"超时/校验失败"根本查不出原因。
        Queue<String> notes = new ConcurrentLinkedQueue<>();

        CompletableFuture<Optional<Drafted>> plannerFuture =
                settings.waitPerDecisionMs() > 0 && settings.plannerRushAnswer() && planner.healthy()
                        ? planner.urgentChoice(point, missReasons)
                              .thenApply(draft -> {
                                  if (draft.isEmpty()) {
                                      notes.add("规划器紧急决策无输出");
                                      return Optional.<Drafted>empty();
                                  }
                                  return buildFromDraft(point, draft.get(), "规划器抢答", notes);
                              })
                        : CompletableFuture.completedFuture(Optional.empty());
        TurnPlan planForOneshot = planner.currentPlan().orElse(null);
        // 这一手是在执行哪个效果的哪一步：从属决策点（选卡/选位置/选表示形式）全靠它才有语义
        String effectScope = pendingEffectTurn == point.turn()
                ? packer.describeEffect(pendingEffectCard, pendingEffectDesc) : "";
        // 这个决策点之前"已经做过什么 + 上一手怎么想的"（单轮调用没有对话历史，必须显式给）
        DecisionMemory memory =
                DecisionMemory.of(
                        effectScope, List.copyOf(turnActions), lastIntent);
        CompletableFuture<Optional<Drafted>> oneshotFuture =
                CompletableFuture.supplyAsync(() -> {
                    String pack = packer.decisionPack(point, planForOneshot, memory);
                    List<ChatClient.Msg> messages = List.of(
                            ChatClient.Msg.system(PlannerPrompts.ONESHOT_SYSTEM),
                            ChatClient.Msg.user(pack));
                    ChatClient.ChatOptions options = ChatClient.ChatOptions.decision(
                                    settings.oneshotMaxTokens(),
                                    Duration.ofMillis(Math.min(settings.oneshotTimeoutMs(), deadlineMs)))
                            // 思考强度走配置（LLM_REASONING_DECISION）；留空 = 沿用决策档默认（关思考）
                            .withReasoning(LlmTuning.get().decision());
                    // 工具按需注册：规则 / 连招 / 查卡，模型自己决定要不要调用（工具轮对上层透明）
                    List<ChatClient.Tool> toolList = tools.tools();
                    String raw;
                    try (var ignored = LlmTrace.scope("决策器判断 T" + point.turn() + " " + point.kind())) {
                        if (toolList.isEmpty()) {
                            raw = chat.complete(messages, options);
                        } else {
                            Set<Long> visibleCards = ComboTool.visibleSelfCards(point.state());
                            raw = chat.completeWithTools(messages, options, toolList,
                                    tools.handler(visibleCards));
                        }
                    }
                    Optional<PlannerSession.ChoiceDraft> draft = PlannerSession.ChoiceDraftParser.parse(raw);
                    if (draft.isEmpty()) {
                        notes.add("单轮直答输出不可解析：" + snippet(raw));
                        return Optional.<Drafted>empty();
                    }
                    Optional<Drafted> built = buildFromDraft(point, draft.get(), "单轮直答", null);
                    if (built.isPresent() || draft.get().choice() == null
                            || point.candidate(draft.get().choice()).isPresent()) {
                        return built.or(() -> {
                            notes.add("单轮直答无法映射成意图：" + draft.get().choice().value());
                            return Optional.<Drafted>empty();
                        });
                    }
                    notes.add("单轮直答选择越界：" + draft.get().choice().value()
                            + "（候选 " + point.candidates().size() + " 个），已要求重答");
                    List<ChatClient.Msg> retryMessages = new ArrayList<>(messages);
                    retryMessages.add(ChatClient.Msg.user(
                            "你的 choice=" + draft.get().choice().value() + " 不在候选集内。"
                                    + "本决策点可选编号：" + candidateCodes(point)
                                    + "（本请求没有 C0）。请只输出 JSON，choice 必须是其中之一。"));
                    ChatClient.ChatOptions retryOptions = ChatClient.ChatOptions.decision(
                                    settings.oneshotMaxTokens(),
                                    Duration.ofMillis(Math.max(600, options.timeout().toMillis() / 2)))
                            .withReasoning(LlmTuning.get().decision());
                    String retried;
                    try (var ignored = LlmTrace.scope("决策器判断重答 T" + point.turn() + " " + point.kind())) {
                        retried = toolList.isEmpty() ? chat.complete(retryMessages, retryOptions)
                                : chat.completeWithTools(retryMessages, retryOptions, toolList,
                                        tools.handler(ComboTool.visibleSelfCards(point.state())));
                    }
                    Optional<PlannerSession.ChoiceDraft> second =
                            PlannerSession.ChoiceDraftParser.parse(retried);
                    if (second.isEmpty()) {
                        notes.add("重答仍不可解析：" + snippet(retried));
                        return Optional.<Drafted>empty();
                    }
                    return buildFromDraft(point, second.get(), "单轮直答·重答", notes);
                }, oneshotPool)
                        .exceptionally(ex -> {
                            log.info("决策器判断调用失败: {}", String.valueOf(ex));
                            notes.add("单轮直答调用失败：" + rootCause(ex));
                            return Optional.empty();
                        });

        long waitPlanner = Math.min(settings.waitPerDecisionMs(),
                Math.min(planner.remainingTurnBudget(), deadlineMs));
        long phase1End = start + Math.max(0, waitPlanner);
        while (System.currentTimeMillis() < phase1End) {
            if (plannerFuture.isDone()) {
                Optional<Drafted> d = joinQuietly(plannerFuture);
                if (d.isPresent()) {
                    return Optional.of(accept(d.get()));
                }
                break; // planner 明确无结果 → 只等 one-shot
            }
            if (oneshotFuture.isDone()) {
                Optional<Drafted> d = joinQuietly(oneshotFuture);
                if (d.isPresent()) {
                    return Optional.of(accept(d.get()));
                }
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        long remaining = Math.max(0, deadlineMs - (System.currentTimeMillis() - start));
        if (remaining <= 0) {
            oneshotFuture.cancel(true);
            notes.add("单轮直答超时：等待阶段已耗尽 " + deadlineMs + "ms");
            missReasons.addAll(notes);
            return Optional.empty();
        }
        try {
            Optional<DuelDecision> decided = oneshotFuture.get(remaining, TimeUnit.MILLISECONDS).map(this::accept);
            missReasons.addAll(notes);
            return decided;
        } catch (TimeoutException e) {
            oneshotFuture.cancel(true);
            notes.add("单轮直答超时：" + (System.currentTimeMillis() - start) + "ms 内未返回");
            missReasons.addAll(notes);
            return Optional.empty();
        } catch (Exception e) {
            notes.add("单轮直答异常：" + rootCause(e));
            missReasons.addAll(notes);
            return Optional.empty();
        }
    }

    /** 原始输出截断（日志/trace 里看得到模型到底吐了什么）。 */
    private static String snippet(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（空）";
        }
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static String rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + snippet(msg));
    }

    private static Optional<Drafted> joinQuietly(CompletableFuture<Optional<Drafted>> future) {
        try {
            return future.getNow(Optional.empty());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** one-shot / 规划器草案 → 决策（候选必须真实存在于请求内，越界即拒）。 */
    private Optional<Drafted> buildFromDraft(DecisionPoint point, PlannerSession.ChoiceDraft draft,
                                             String source) {
        return buildFromDraft(point, draft, source, null);
    }

    /**
     * @param notes 失败原因收集器（可为 null）；"选择越界/无法映射成意图"这类静默失败必须留下痕迹，
     *              否则上层只会看到一句笼统的"校验失败"。
     */
    private Optional<Drafted> buildFromDraft(DecisionPoint point, PlannerSession.ChoiceDraft draft,
                                             String source, Queue<String> notes) {
        CandidateId id = draft.choice();
        // 兼容：模型偶尔仍会写 C0（旧提示词的习惯）。此时把它当成本请求的弃权候选，
        // 而不是判越界——弃权本身是合法动作，不该因为编号写法不同就丢掉整条决策。
        if (isNoneChoice(id)) {
            Optional<ChoiceIntent> none = point.candidatesOf(Candidate.DeclineC.class).stream()
                    .findFirst()
                    .flatMap(decline -> CandidateIntents.toIntent(point, decline));
            if (none.isEmpty()) {
                none = noneIntent(point);
            }
            if (none.isPresent()) {
                return Optional.of(new Drafted(new DuelDecision(none.get(), PathKind.LLM_CALL,
                        Math.max(0.3, draft.confidence()),
                        DecisionExplanation.of(withRationale(source, draft.rationale())),
                        Timing.ZERO), null));
            }
        }
        Optional<Candidate> candidate = point.candidate(id);
        if (candidate.isEmpty()) {
            log.info("{} 选择越界: {}（候选 {} 个）", source, id.value(), point.candidates().size());
            if (notes != null) {
                notes.add(source + " 选择越界：" + id.value() + "（候选 " + point.candidates().size() + " 个）");
            }
            return Optional.empty();
        }
        Optional<ChoiceIntent> intent = CandidateIntents.toIntent(point, candidate.get());
        if (intent.isEmpty()) {
            if (notes != null) {
                notes.add(source + " 无法映射成意图：" + id.value() + "（" + candidate.get().getClass().getSimpleName() + "）");
            }
            return Optional.empty();
        }
        String rationale = draft.rationale() == null ? "" : draft.rationale();
        return Optional.of(new Drafted(new DuelDecision(intent.get(), PathKind.LLM_CALL,
                Math.max(0.3, draft.confidence()),
                DecisionExplanation.of(withRationale(source, rationale)), Timing.ZERO), draft.zone()));
    }

    /** 来源 + 模型理由 → 一句解释；理由为空时只留来源，不留悬空的分隔符。 */
    private static String withRationale(String source, String rationale) {
        String clean = rationale == null ? "" : rationale.trim();
        return clean.isEmpty() ? source : source + "：" + clean;
    }

    private static String candidateCodes(DecisionPoint point) {
        return point.candidates().stream()
                .map(c -> c.id().value())
                .distinct()
                .collect(Collectors.joining("、"));
    }

    private static boolean isNoneChoice(CandidateId id) {
        String v = id == null ? "" : id.value().trim().toUpperCase(Locale.ROOT);
        return "C0".equals(v) || "0".equals(v) || "NONE".equals(v) || "PASS".equals(v)
                || "不响应".equals(v) || "弃权".equals(v) || "取消".equals(v);
    }

    private static Optional<ChoiceIntent> noneIntent(DecisionPoint point) {
        ChoiceRequest r = point.request();
        if (r instanceof ChoiceRequest.ChainRequest) {
            return Optional.of(new ChoiceIntent.ChainDecline());
        }
        if (r instanceof ChoiceRequest.EffectYesNoRequest) {
            return Optional.of(new ChoiceIntent.EffectYesNo(false));
        }
        if (r instanceof ChoiceRequest.YesNoRequest) {
            return Optional.of(new ChoiceIntent.YesNo(false));
        }
        if (r instanceof ChoiceRequest.CardSelectRequest c
                && c.cancelable()) {
            return Optional.of(new ChoiceIntent.Cancel());
        }
        if (r instanceof ChoiceRequest.UnselectRequest u) {
            if (u.finishable()) {
                return Optional.of(new ChoiceIntent.UnselectFinish());
            }
            if (u.cancelable()) {
                return Optional.of(new ChoiceIntent.Cancel());
            }
        }
        return Optional.empty();
    }

    /** "被别人触发"的请求：不等计划，直接走 one-shot（时间敏感，且 one-shot 看得到同一份局面）。 */
    private static boolean reactive(RequestKind kind) {
        return kind == RequestKind.CHAIN || kind == RequestKind.EFFECT_YES_NO;
    }

    private static Integer planStepOf(String conclusion) {
        if (conclusion == null) {
            return null;
        }
        Matcher m = Pattern.compile("计划步#(\\d+)").matcher(conclusion);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 高 stakes 判定（slice 1 的确定性近似：对方已知攻击总和可斩 或 我方 LP 危急）。 */
    private boolean highStakes(DecisionPoint point) {
        var state = point.state();
        int oppAttack = state.monsters(PlayerSide.OPPONENT).stream()
                .filter(c -> c.position() != null && c.position().isAttack())
                .mapToInt(c -> facts.byPasscode(c.passcode()).map(CardFact::atk).orElse(0))
                .sum();
        return (oppAttack >= state.self().lifePoints() && oppAttack > 0)
                || state.self().lifePoints() <= 3000;
    }


    private void event(DecisionPoint point, String kind, Map<String, Object> payload) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(payload);
            data.put("seq", point.seq());
            data.put("turn", point.turn());
            data.put("phase", String.valueOf(point.phase()));
            data.put("requestKind", point.kind().name());
            traceWriter.event(kind, data);
        } catch (RuntimeException ignored) {
        }
    }

    private static String explainStep(PlanStep step) {
        Object why = step.expectedEffects().get("why");
        if (why != null) {
            return String.valueOf(why);
        }
        return step.alternatives().isEmpty() ? "" : step.alternatives().getFirst().verb();
    }

    @Override
    public void close() {
        oneshotPool.shutdownNow();
        planner.close();
    }
}
