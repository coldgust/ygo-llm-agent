package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.llm.DecisionTools;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.judge.ChoiceDraft;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.RulebookTool;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.pipeline.plan.PlanTreeParser;

/**
 * DuelPlanner 的持续 session（v6 §2.1 / §3）：
 * <ul>
 *   <li>回合分段：每个"回合数+回合方"一段；段间由确定性摘要接力（最近 thinking + 目标）</li>
 *   <li>锚点：段首注入 ContextPack 规范化快照，并请求本回合计划树</li>
 *   <li>事件批：决策应答/关键事件累积，checkpoint 时整批发送；LLM 回 keep 或新计划</li>
 *   <li>发散：④ 升级时把计划外局面送进 session 请求紧急选择（同步等待，受预算约束）</li>
 *   <li>降级：LLM 失败进入冷却期，期间所有请求快速返回空（执行器落到 直答/保底）</li>
 * </ul>
 * 全部 LLM 调用在单线程后台执行器上运行；对外状态为不可变快照（无锁读）。
 */
public final class PlannerSession implements AutoCloseable {
    /**
     * 两次事件检查点之间的最小间隔：避免"深度档"连续猛跑把 LLM 配额和一个 worker 线程全占住，
     * 导致后续回合的锚点排不上队。
     */
    private static final long EVENT_CHECKPOINT_GAP_MS = 1_500;

    private static final Logger log = LoggerFactory.getLogger(PlannerSession.class);
    private static final Pattern CHOICE_PATTERN = Pattern.compile("\"choice\"\\s*:\\s*\"?(C\\d+)\"?");

    private final ChatClient client;
    private final ContextPackBuilder packer;
    private final CardFacts facts;
    /** 规划器可用的工具（规则 / 查卡 / 连招）。至少含 find_card：卡面不确定时要查，不能凭记忆。 */
    private final DecisionTools tools;
    private final PipelineSettings settings;
    private final DecisionTraceWriter traceWriter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ygo-planner");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<TurnPlan> currentPlan = new AtomicReference<>();
    private final AtomicReference<Instant> degradedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong checkpointCount = new AtomicLong();
    /** 本段是否已因"keep 但无计划"补过一次（防止每轮都补，刷爆配额）。 */
    private final Set<String> keepWithoutPlanRetried = ConcurrentHashMap.newKeySet();
    /** 事件检查点是否已有任务在排队/执行：有则不再排新任务，事件攒到下一批一起发。 */
    private final AtomicBoolean checkpointQueued =
            new AtomicBoolean();
    private final AtomicLong turnBudgetUsedMs = new AtomicLong();

    private String segmentKey = "";
    private final List<ChatMessage> history = new ArrayList<>();
    private final List<String> pendingEvents = new ArrayList<>();
    private final Set<Integer> planWaitExhausted = ConcurrentHashMap.newKeySet();
    private final VerboseLog trace;

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings, VerboseLog trace) {
        this(client, packer, facts, settings, null,
                (DecisionTools) null, trace);
    }

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings,
                          DecisionTraceWriter traceWriter, VerboseLog trace) {
        this(client, packer, facts, settings, traceWriter,
                (DecisionTools) null, trace);
    }

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings,
                          DecisionTraceWriter traceWriter,
                          RulebookTool rulebook, VerboseLog trace) {
        this(client, packer, facts, settings, traceWriter,
                rulebook == null ? null
                        : new DecisionTools(rulebook, null, null), trace);
    }

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings,
                          DecisionTraceWriter traceWriter,
                          DecisionTools tools, VerboseLog trace) {
        this.client = client;
        this.packer = packer;
        this.facts = facts;
        this.settings = settings;
        this.tools = tools;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
        this.traceWriter = traceWriter == null
                ? DecisionTraceWriter.NOOP : traceWriter;
    }

    private void planEvent(String kind, Map<String, Object> payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("planKind", kind);
            traceWriter.event("PLAN_EVENT", event);
        } catch (RuntimeException ignored) {
        }
    }


    public void observePoint(DecisionPoint point) {
        String key = point.turn() + ":" + point.state().turnPlayer();
        if (!key.equals(segmentKey)) {
            String anchor = packer.plannerAnchorPack(point.state());
            String side = point.state().isMyTurn() ? "我方回合" : "对方回合";
            List<Long> handCodes = point.state().self().hand().stream()
                    .map(c -> c.passcode()).filter(c -> c > 0).toList();
            worker.execute(() -> rotateAndAnchor(key, point.turn(), side, anchor, handCodes));
        }
    }

    public void observeAnswered(DecisionPoint point, ChoiceIntent intent) {
        String line = "第" + point.turn() + "回合 " + point.phase() + " 应答 "
                + point.kind() + ": " + describe(point, intent);
        synchronized (pendingEvents) {
            pendingEvents.add(line);
        }
    }

    /**
     * ④ 升级路径：把计划外局面送进 session 请求紧急选择。
     * 同步等待由调用方控制（wait-per-decision 预算）；session 内部也要排 planner 线程的队。
     */
    public CompletableFuture<Optional<ChoiceDraft>> urgentChoice(DecisionPoint point, List<String> missReasons) {
        long start = System.nanoTime();
        long allowed = Math.min(settings.waitPerDecisionMs(), remainingTurnBudget());
        if (allowed <= 200 || degraded()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<ChoiceDraft>> future = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                String pack = packer.decisionPack(point);
                String prompt = PlannerPrompts.DIVERGENCE_TEMPLATE.formatted(
                        String.join("；", missReasons), pack);
                List<ChatMessage> messages = new ArrayList<>(historySnapshot());
                messages.add(UserMessage.from(prompt));
                ChatClient.ChatOptions options = ChatClient.ChatOptions.decision(
                                settings.judgeMaxTokens(), Duration.ofMillis(Math.max(500, allowed)))
                        .withReasoning(settings.reasoningDecision());
                String raw;
                try (var ignored = trace.scope("规划器紧急决策")) {
                    raw = withTools(messages, options);
                }
                rememberAssistant(raw);
                turnBudgetUsedMs.addAndGet((System.nanoTime() - start) / 1_000_000);
                future.complete(ChoiceDraft.parse(raw));
            } catch (RuntimeException e) {
                log.info("规划器紧急决策失败: {}", e.toString());
                degrade();
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    /**
     * 作废当前计划并请求重新规划（执行器发现"剩余步骤全部接不上地"时调用）。
     */
    public void abandonAndReplan(String reason) {
        TurnPlan stale = currentPlan.getAndSet(null);
        if (stale == null) {
            return;
        }
        if (stale.steps().isEmpty()) {
            // 已经是"只剩战术思想"的骨架：不再重复请求重规划（避免每个决策都触发一次）
            currentPlan.set(stale);
            return;
        }
        // 路径作废，但战术思想留着：决策器没有主线时会打出与整体思路背道而驰的操作，
        // 所以把 thinking/goal 作为"骨架计划"继续喂给 直答（steps/branches 清空、置信度减半）。
        currentPlan.set(new TurnPlan(stale.planId(), stale.turnNumber(), stale.thinking(), stale.goal(),
                List.of(), List.of(), 0, Math.max(0.2, stale.confidence() * 0.5)));
        log.info("计划作废 {}（{}），改决策器判断并请求重新规划", stale.planId(), reason);
        planEvent("PLAN_ABANDONED", Map.of("planId", stale.planId(), "cursor", stale.cursor(),
                "steps", stale.steps().size(), "reason", String.valueOf(reason)));
        if (segmentKey.isEmpty() || degraded()) {
            return;
        }
        if (checkpointCount.get() >= settings.maxCheckpointsPerTurn()) {
            log.info("本回合 checkpoint 已用尽，不请求重新规划（保持决策器判断）");
            return;
        }
        String segment = segmentKey;
        worker.execute(() -> {
            if (!segment.equals(segmentKey)) {
                return; // 已经换回合：旧计划作废就算了，新回合的锚点会重新规划
            }
            history.add(UserMessage.from(PlannerPrompts.REPLAN_TEMPLATE.formatted(
                    stale.planId(), stale.cursor() + 1, String.valueOf(reason))));
            runCheckpoint("replan");
        });
    }

    public Optional<TurnPlan> currentPlan() {
        return Optional.ofNullable(currentPlan.get());
    }

    public void advanceCursorTo(int nextCursor) {
        TurnPlan plan = currentPlan.get();
        if (plan != null && nextCursor > plan.cursor()) {
            currentPlan.set(new TurnPlan(plan.planId(), plan.turnNumber(), plan.thinking(), plan.goal(),
                    plan.steps(), plan.branches(), Math.min(nextCursor, plan.steps().size()), plan.confidence()));
        }
    }

    /**
     * 决策时等待规划器：锚点竞速场景下，等"本回合计划"发布（§4 决策时等待规划器预算）。
     * 不健康/超时 → empty（调用方落到 直答/保底）。
     */
    public Optional<TurnPlan> waitForPlan(int turn, long waitMs) {
        if (waitMs <= 0 || degraded() || planWaitExhausted.contains(turn)) {
            return Optional.empty(); // 本回合等待超时过：后续点直接走 ④，不重复卡顿
        }
        long start = System.currentTimeMillis();
        long deadline = start + waitMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                TurnPlan plan = currentPlan.get();
                if (plan != null && plan.turnNumber() == turn) {
                    return Optional.of(plan);
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 等待也要计入"本回合为等规划器而推迟应答"的额度：首动作多等了 7s 之后，
            // 后续 urgentChoice 若仍按满额累加，一回合的应答时间会被悄悄花光。
            turnBudgetUsedMs.addAndGet(System.currentTimeMillis() - start);
        }
        // 超时标记：本回合不再重复等待。
        if (waitMs >= settings.waitPerDecisionMs()) {
            planWaitExhausted.add(turn);
        }
        return Optional.empty();
    }

    /** 本回合"等规划器"的剩余额度（turnBudgetUsedMs 由 waitForPlan 与 urgent 路径累计）。 */
    public long remainingTurnBudget() {
        return Math.max(0, settings.waitPerTurnMs() - turnBudgetUsedMs.get());
    }

    public boolean healthy() {
        return !degraded();
    }

    public void drainForTests() {
        try {
            worker.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("drain failed", e);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }


    private void rotateAndAnchor(String key, int turn, String side, String anchor, List<Long> handCodes) {
        String handoff = segmentKey.isEmpty() ? "" : handoffSummary();
        segmentKey = key;
        history.clear();
        // 顾问模式（默认）：只要场面分析/战术/combo 的自然语言，不产出可执行路径。
        // 路径规划模式（PLANNER_PATH_PLANNING=true）：回到 v6 原设计，产出 steps/branches。
        history.add(SystemMessage.from(settings.pathPlanning()
                ? PlannerPrompts.PLANNER_SYSTEM
                : PlannerPrompts.ADVISOR_SYSTEM));
        if (!handoff.isEmpty()) {
            history.add(UserMessage.from("===上一段摘要接力===\n" + handoff));
        }
        boolean oppTurn = side.contains("对方");
        boolean pathPlanning = settings.pathPlanning();
        String anchorRequest;
        if (pathPlanning) {
            anchorRequest = oppTurn
                    ? PlannerPrompts.ANCHOR_OPP_TEMPLATE.formatted(turn, anchor)
                    : PlannerPrompts.ANCHOR_TEMPLATE.formatted(turn, side, anchor);
        } else {
            anchorRequest = oppTurn
                    ? PlannerPrompts.ADVISOR_ANCHOR_OPP_TEMPLATE.formatted(turn, anchor)
                    : PlannerPrompts.ADVISOR_ANCHOR_TEMPLATE.formatted(turn, side, anchor);
        }
        history.add(UserMessage.from(anchorRequest));
        checkpointCount.set(0);
        turnBudgetUsedMs.set(0);
        planWaitExhausted.clear();
        synchronized (pendingEvents) {
            pendingEvents.clear();
        }
        runCheckpoint("anchor");
    }

    /**
     * checkpoint：发送事件批并请求 keep/新计划（异步）。
     */
    public void flushEvents() {
        if (segmentKey.isEmpty()) {
            return;
        }
        if (checkpointCount.get() >= settings.maxCheckpointsPerTurn()) {
            return; // 成本闸：本回合后续事件继续累积，不调用
        }
        scheduleEventCheckpoint();
    }

    private void scheduleEventCheckpoint() {
        if (!checkpointQueued.compareAndSet(false, true)) {
            return; // 已有在途检查点：事件留在 pendingEvents，等它结束后一起发
        }
        String segment = segmentKey;
        worker.execute(() -> {
            try {
                if (!segment.equals(segmentKey)) {
                    return; // 段已轮换：这批事件属于上一回合，别再喂给新回合的 history
                }
                List<String> events;
                synchronized (pendingEvents) {
                    if (pendingEvents.isEmpty()) {
                        return;
                    }
                    events = new ArrayList<>(pendingEvents);
                    pendingEvents.clear();
                }
                if (checkpointCount.get() >= settings.maxCheckpointsPerTurn()) {
                    return;
                }
                history.add(UserMessage.from(PlannerPrompts.EVENT_BATCH_TEMPLATE.formatted(
                        String.join("\n", events))));
                runCheckpoint("events");
            } finally {
                checkpointQueued.set(false);
                // 自排空：检查点期间又攒了新事件 → 立刻再发一批（仍受配额与间隔约束）
                if (!segment.equals(segmentKey)) {
                    return;
                }
                boolean more;
                synchronized (pendingEvents) {
                    more = !pendingEvents.isEmpty();
                }
                if (more && checkpointCount.get() < settings.maxCheckpointsPerTurn()) {
                    try {
                        Thread.sleep(EVENT_CHECKPOINT_GAP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    scheduleEventCheckpoint();
                }
            }
        });
    }

    private String withTools(List<ChatMessage> messages, ChatClient.ChatOptions options) {
        if (tools == null || tools.isEmpty()) {
            return client.complete(messages, options);
        }
        List<Object> providers = tools.providers();
        if (providers.isEmpty()) {
            return client.complete(messages, options);
        }
        // 规划器不涉及"手上哪张卡可见"的过滤（旧路线的痕迹；检索本身也不再依赖）
        return client.completeWithTools(messages, options, providers);
    }

    private void runCheckpoint(String kind) {
        if (degraded()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            // checkpoint 档位一键由 LLM_REASONING_PLANNER 决定（未配置=low 推理档；off=快档），
            // 锚点/重规划/增量修订不再分档，选项推导见 PipelineSettings.checkpointOptions。
            ChatClient.ChatOptions options = settings.checkpointOptions();
            List<ChatMessage> prompt = List.copyOf(history);
            String raw;
            try (var ignored = trace.scope("规划器 " + kind)) {
                raw = withTools(prompt, options);
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            if (raw == null || raw.isBlank()) {
                // 模型偶尔返回空串（截断/服务端抖动）：这不是"计划非法"，按 keep 处理别降级
                log.info("规划器返回空内容（kind={}），保持当前计划", kind);
                planEvent("PLAN_EMPTY", Map.of("checkpoint", String.valueOf(kind)));
                return;
            }
            rememberAssistant(raw);
            checkpointCount.incrementAndGet();
            if (isKeep(raw)) {
                if (currentPlan.get() == null) {
                    log.info("规划器回 keep 但当前没有计划（kind={}），改请求重新规划", kind);
                    planEvent("PLAN_KEEP_WITHOUT_PLAN", Map.of("checkpoint", String.valueOf(kind)));
                    if (keepWithoutPlanRetried.add(segmentKey)) {
                        history.add(UserMessage.from(settings.pathPlanning()
                                ? PlannerPrompts.KEEP_RECOVERY_TEMPLATE.formatted(String.valueOf(kind))
                                : PlannerPrompts.ADVISOR_KEEP_RECOVERY_TEMPLATE.formatted(String.valueOf(kind))));
                        runCheckpoint("replan");
                    }
                    return;
                }
                carryOverKeptPlan(kind);
                return;
            }
            PlanTreeParser.Result parsed = PlanTreeParser.parseDetailed(raw, this::isKnownCard);
            if (parsed != null && parsed.plan() != null) {
                PlanTreeParser.ParsedPlan plan = parsed.plan();
                String planId = "P" + segmentKey + "#" + checkpointCount.get();
                currentPlan.set(new TurnPlan(planId, turnNumberOfSegment(), plan.thinking(), plan.goal(),
                        plan.steps(), plan.branches(), 0, plan.confidence()));
                log.info("规划器产出计划 {}（{}ms，steps={} branches={} 丢弃非法分支步={}）", planId,
                        elapsed, plan.steps().size(), plan.branches().size(), plan.droppedSteps());
                List<Map<String, Object>> stepDetails = plan.steps().stream()
                        .map(st -> Map.<String, Object>of(
                                "index", st.index(),
                                "when", st.expectedRequest().name(),
                                "verb", st.alternatives().isEmpty() ? ""
                                        : st.alternatives().getFirst().verb(),
                                "passcode", st.alternatives().isEmpty() ? 0L
                                        : st.alternatives().getFirst().passcode(),
                                // 位置也是计划的一部分：前端要能看出"召唤到哪一格"
                                "zone", st.alternatives().isEmpty()
                                        || st.alternatives().getFirst().zone() == null ? ""
                                        : ZoneCodes.code(
                                                st.alternatives().getFirst().zone()),
                                "why", String.valueOf(st.expectedEffects().getOrDefault("why", ""))))
                        .toList();
                // 分支明细：前端要能展开看"如果对手X就Y"
                List<Map<String, Object>> branchDetails = plan.branches().stream()
                        .map(br -> Map.<String, Object>of(
                                "name", br.name(),
                                "assumes", br.assumes().toString(),
                                "steps", br.steps().stream()
                                        .map(st -> (st.alternatives().isEmpty() ? "?"
                                                : st.alternatives().getFirst().verb())
                                                + (st.alternatives().isEmpty()
                                                || st.alternatives().getFirst().passcode() <= 0 ? ""
                                                : " " + st.alternatives().getFirst().passcode()))
                                        .toList()))
                        .toList();
                planEvent("PLAN_PUBLISHED", Map.of(
                        "planId", planId, "steps", plan.steps().size(),
                        "branches", plan.branches().size(), "confidence", plan.confidence(),
                        "thinking", plan.thinking(), "goal", plan.goal(),
                        "checkpoint", kind, "elapsedMs", elapsed,
                        "stepDetails", stepDetails, "branchDetails", branchDetails));
            } else {
                String snippet = raw.length() > 1_200 ? raw.substring(0, 1_200) : raw;
                String reason = parsed == null ? "" : parsed.failure();
                log.warn("规划器输出无法解析为计划（kind={}，原因：{}）；原始输出前 1200 字：\n{}",
                        kind, reason, snippet);
                planEvent("PARSE_FAILED", Map.of("checkpoint", String.valueOf(kind),
                        "reason", String.valueOf(reason), "raw", snippet));
            }
        } catch (RuntimeException e) {
            log.warn("规划器 checkpoint 失败（kind={}）: {}", kind, e.toString());
            planEvent("CHECKPOINT_FAILED", Map.of("checkpoint", String.valueOf(kind)));
            degrade();
        }
    }

    /**
     * 规划器回 keep：把上一份计划"续期"到当前回合。
     * <p>步骤与分支保持不变，cursor 归零（重新从第 1 步开始接地），置信度打折——
     * 毕竟它是上一回合做的判断。这样执行器 ②③ 与 直答 的计划上下文都不再"空转"。
     */
    private void carryOverKeptPlan(String kind) {
        TurnPlan previous = currentPlan.get();
        int turn = turnNumberOfSegment();
        if (previous == null || previous.turnNumber() == turn) {
            return;
        }
        double confidence = Math.max(0.3, previous.confidence() * 0.7);
        currentPlan.set(new TurnPlan(previous.planId(), turn, previous.thinking(), previous.goal(),
                previous.steps(), previous.branches(), 0, confidence));
        log.info("规划器回 keep（{}）：第 {} 回合的计划续到第 {} 回合（steps={} branches={} 置信 {}）",
                kind, previous.turnNumber(), turn, previous.steps().size(),
                previous.branches().size(), String.format(Locale.ROOT, "%.2f", confidence));
        planEvent("PLAN_CARRIED_OVER", Map.of(
                "checkpoint", String.valueOf(kind),
                "planId", previous.planId(),
                "fromTurn", previous.turnNumber(),
                "turn", turn,
                "steps", previous.steps().size(),
                "confidence", confidence));
    }

    private boolean isKnownCard(long passcode) {
        return facts.byPasscode(passcode).isPresent();
    }

    private int turnNumberOfSegment() {
        int colon = segmentKey.indexOf(':');
        try {
            return colon > 0 ? Integer.parseInt(segmentKey.substring(0, colon)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 段间接力的确定性摘要：最近 thinking + goal + 事件数（不额外调 LLM，v1 简化）。 */
    private String handoffSummary() {
        TurnPlan plan = currentPlan.get();
        if (plan == null) {
            return "（上一段无有效计划）";
        }
        return "- 最近思路：" + plan.thinking() + "\n- 目标：" + plan.goal()
                + "\n- 计划完成度：" + plan.cursor() + "/" + plan.steps().size();
    }

    private void rememberAssistant(String raw) {
        history.add(AiMessage.from(raw));
        // 防膨胀：段内历史超过 24 条时丢弃最早的 assistant 长文（保留 system 与最近 8 条）
        if (history.size() > 24) {
            history.subList(1, Math.max(1, history.size() - 8)).clear();
        }
    }

    private List<ChatMessage> historySnapshot() {
        return List.copyOf(history);
    }

    private static boolean isKeep(String raw) {
        return raw != null && raw.contains("\"keep\"") && raw.toLowerCase(Locale.ROOT).contains("true");
    }

    private boolean degraded() {
        return Instant.now().isBefore(degradedUntil.get());
    }

    private void degrade() {
        degradedUntil.set(Instant.now().plusMillis(settings.plannerCooldownMs()));
    }

    private String describe(DecisionPoint point, ChoiceIntent intent) {
        String label = candidateLabel(point, intent);
        return switch (intent) {
            case ChoiceIntent.Summon ignored -> "通常召唤 " + label;
            case ChoiceIntent.SpecialSummon ignored -> "特殊召唤 " + label;
            case ChoiceIntent.Reposition ignored -> "改变表示形式 " + label;
            case ChoiceIntent.SetMonster ignored -> "盖放怪兽 " + label;
            case ChoiceIntent.SetSpellTrap ignored -> "盖放魔陷 " + label;
            case ChoiceIntent.Activate ignored -> "发动 " + label;
            case ChoiceIntent.BattleActivate ignored -> "战阶发动 " + label;
            case ChoiceIntent.Attack ignored -> "攻击宣言 " + label;
            case ChoiceIntent.ChainRespond ignored -> "连锁 " + label;
            case ChoiceIntent.ChainDecline ignored -> "不连锁";
            case ChoiceIntent.EnterBattlePhase ignored -> "进战阶";
            case ChoiceIntent.EndTurn ignored -> "结束回合";
            case ChoiceIntent.EnterMainPhase2 ignored -> "进 M2";
            case ChoiceIntent.EndBattlePhase ignored -> "结束战斗";
            case ChoiceIntent.Select selected -> "选卡 " + labels(point, selected.cards());
            case ChoiceIntent.Tribute t -> "祭品 " + labels(point, t.cards());
            case ChoiceIntent.Sum s -> "凑和 " + labels(point, s.cards());
            case ChoiceIntent.EffectYesNo yes -> "效果YesNo=" + yes.activate();
            case ChoiceIntent.YesNo yes -> "YesNo=" + yes.yes();
            default -> intent.getClass().getSimpleName();
        };
    }

    private String labels(DecisionPoint point, List<CandidateId> ids) {
        StringJoinerLite sj = new StringJoinerLite("、");
        ids.forEach(id -> sj.add(candidateLabel(point, intentOf(id))));
        return sj.toString();
    }

    private ChoiceIntent intentOf(CandidateId id) {
        return new ChoiceIntent.Activate(id); // 仅借 label 通道；CandidateLabel 不关心 intent 类型
    }

    private String candidateLabel(DecisionPoint point, ChoiceIntent intent) {
        CandidateId id = candidateIdOf(intent);
        if (id == null) {
            return "";
        }
        return point.candidate(id)
                .map(c -> switch (c) {
                    case Candidate.CardC card -> DuelMathService.labelOf(
                            new CardInstance(
                                    card.value().instanceId(), card.value().identity(),
                                    card.value().zone(), null, null, null), facts);
                    case Candidate.EffectC effect -> DuelMathService.labelOf(
                            new CardInstance(
                                    effect.value().card().instanceId(), effect.value().card().identity(),
                                    effect.value().card().zone(), null, null, null), facts)
                            + "[效果#" + effect.value().description() + "]";
                    case Candidate.AttackC attack -> DuelMathService.labelOf(
                            new CardInstance(
                                    attack.value().card().instanceId(), attack.value().card().identity(),
                                    attack.value().card().zone(), null, null, null), facts);
                    case Candidate.TributeC tribute -> DuelMathService.labelOf(
                            new CardInstance(
                                    tribute.value().card().instanceId(), tribute.value().card().identity(),
                                    tribute.value().card().zone(), null, null, null), facts);
                    case Candidate.ZoneC zone -> DuelMathService.zoneLabel(zone.value().kind())
                            + "#" + zone.value().sequence();
                    case Candidate.CounterC ignored -> "指示物";
                    case Candidate.FlagC flag -> flag.flag().label();
                    case Candidate.DeclineC decline -> decline.kind().label();
                })
                .orElse(id.value());
    }

    private static CandidateId candidateIdOf(ChoiceIntent intent) {
        return switch (intent) {
            case ChoiceIntent.Summon i -> i.card();
            case ChoiceIntent.SpecialSummon i -> i.card();
            case ChoiceIntent.Reposition i -> i.card();
            case ChoiceIntent.SetMonster i -> i.card();
            case ChoiceIntent.SetSpellTrap i -> i.card();
            case ChoiceIntent.Activate i -> i.effect();
            case ChoiceIntent.BattleActivate i -> i.effect();
            case ChoiceIntent.Attack i -> i.attack();
            case ChoiceIntent.ChainRespond i -> i.option();
            case ChoiceIntent.Select s -> s.cards().isEmpty() ? null : s.cards().getFirst();
            case ChoiceIntent.Tribute t -> t.cards().isEmpty() ? null : t.cards().getFirst();
            case ChoiceIntent.Sum s -> s.cards().isEmpty() ? null : s.cards().getFirst();
            default -> null;
        };
    }

    /** 极简 joiner（避免额外依赖）。 */
    private static final class StringJoinerLite {
        private final StringBuilder sb = new StringBuilder();
        private final String delimiter;
        private boolean first = true;

        StringJoinerLite(String delimiter) {
            this.delimiter = delimiter;
        }

        void add(String item) {
            if (item == null || item.isEmpty()) {
                return;
            }
            if (!first) {
                sb.append(delimiter);
            }
            sb.append(item);
            first = false;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

}
