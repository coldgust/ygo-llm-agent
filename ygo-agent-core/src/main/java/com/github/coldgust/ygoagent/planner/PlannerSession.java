package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelTimeline;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.DecisionTools;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.judge.ChoiceDraft;
import com.github.coldgust.ygoagent.pipeline.plan.PlanTreeParser;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DuelPlanner 的持续 session 门面（v6 §2.1 / §3）：只做编排，不持有可拆状态。
 * <ul>
 *   <li>回合分段：每个"回合数+回合方"一段（{@link PlannerHistory} 持有对话，段间确定性接力）；</li>
 *   <li>锚点：段首注入局面/记录/卡面 + 静态资料，请求本回合计划/建议（{@link PlanKeeper} 持有计划状态）；</li>
 *   <li>事件批：决策应答与对局记录增量在 checkpoint 时整批发送
 *       （调度状态与配额在 {@link EventCheckpoints}）；</li>
 *   <li>发散：④ 升级时把计划外局面送进 session 请求紧急选择（同步等待，受预算约束）；</li>
 *   <li>降级：LLM 失败进入冷却期，期间所有请求快速返回空（执行器落到 直答/保底）。</li>
 * </ul>
 * 全部 LLM 调用在单线程后台执行器上运行；对外状态为不可变快照（无锁读）。
 */
public final class PlannerSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlannerSession.class);

    private final ChatClient client;
    private final ContextPackBuilder packer;
    private final CardFacts facts;
    /** 规划器可用的工具（规则 / 查卡 / 连招）。至少含 find_card：卡面不确定时要查，不能凭记忆。 */
    private final DecisionTools tools;
    private final PipelineSettings settings;
    private final DecisionTraceWriter traceWriter;
    /** 决斗过程记录端口（可空）。事件批由此增量补"对方做了什么"——pendingEvents 只有我方应答。 */
    private final DuelTimeline timeline;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ygo-planner");
        t.setDaemon(true);
        return t;
    });

    private final PlannerHistory history = new PlannerHistory();
    private final PlanKeeper keeper = new PlanKeeper();
    private final EventCheckpoints checkpoints = new EventCheckpoints();
    private final AnswerDescriber describer;
    private final AtomicReference<Instant> degradedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong checkpointCount = new AtomicLong();
    /** 本段是否已因"keep 但无计划"补过一次（防止每轮都补，刷爆配额）。 */
    private final Set<String> keepWithoutPlanRetried = ConcurrentHashMap.newKeySet();
    private final List<String> pendingEvents = new ArrayList<>();
    private final Set<Integer> planWaitExhausted = ConcurrentHashMap.newKeySet();
    private final VerboseLog trace;

    private String segmentKey = "";

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings, VerboseLog trace) {
        this(client, packer, facts, settings, null, null, null, trace);
    }

    public PlannerSession(ChatClient client, ContextPackBuilder packer, CardFacts facts,
                          PipelineSettings settings, DecisionTraceWriter traceWriter,
                          DecisionTools tools, DuelTimeline timeline, VerboseLog trace) {
        this.client = client;
        this.packer = packer;
        this.facts = facts;
        this.settings = settings;
        this.timeline = timeline;
        this.tools = tools;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
        this.traceWriter = traceWriter == null ? DecisionTraceWriter.NOOP : traceWriter;
        this.describer = new AnswerDescriber(facts);
    }

    private void planEvent(String kind, Map<String, Object> payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("planKind", kind);
            traceWriter.event("PLAN_EVENT", event);
        } catch (RuntimeException ignored) {
            // 轨迹写入不得影响规划
        }
    }


    public void observePoint(DecisionPoint point) {
        String key = point.turn() + ":" + point.state().turnPlayer();
        if (!key.equals(segmentKey)) {
            String anchor = packer.plannerAnchorPack(point.state());
            // 记录水位对齐到锚点内容构建时刻：锚点里的 [决斗记录] 已包含到此为止的事件，
            // 之后的事件批从这条水位继续，不会把同一段事件发两遍
            long recordSeq = timeline == null ? 0L : timeline.latestSeq();
            String side = point.state().isMyTurn() ? "我方回合" : "对方回合";
            worker.execute(() -> rotateAndAnchor(key, point.turn(), side, anchor, recordSeq));
        }
    }

    public void observeAnswered(DecisionPoint point, ChoiceIntent intent) {
        String line = "第" + point.turn() + "回合 " + point.phase() + " 应答 "
                + point.kind() + ": " + describer.describe(point, intent);
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
                List<ChatMessage> messages = new ArrayList<>(history.snapshot());
                messages.add(UserMessage.from(prompt));
                ChatClient.ChatOptions options = ChatClient.ChatOptions.decision(
                                settings.judgeMaxTokens(), Duration.ofMillis(Math.max(500, allowed)))
                        .withReasoning(settings.reasoningDecision());
                String raw;
                try (var ignored = trace.scope("规划器紧急决策")) {
                    raw = withTools(messages, options);
                }
                history.addAssistant(raw);
                checkpoints.addTurnBudget((System.nanoTime() - start) / 1_000_000);
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
        Optional<TurnPlan> abandoned = keeper.abandonToSkeleton();
        if (abandoned.isEmpty()) {
            return;
        }
        TurnPlan stale = abandoned.get();
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
        return keeper.current();
    }

    public void advanceCursorTo(int nextCursor) {
        keeper.advanceCursorTo(nextCursor);
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
                Optional<TurnPlan> plan = keeper.current().filter(p -> p.turnNumber() == turn);
                if (plan.isPresent()) {
                    return plan;
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 等待也要计入"本回合为等规划器而推迟应答"的额度：首动作多等了 7s 之后，
            // 后续 urgentChoice 若仍按满额累加，一回合的应答时间会被悄悄花光。
            checkpoints.addTurnBudget(System.currentTimeMillis() - start);
        }
        // 超时标记：本回合不再重复等待。
        if (waitMs >= settings.waitPerDecisionMs()) {
            planWaitExhausted.add(turn);
        }
        return Optional.empty();
    }

    /** 本回合"等规划器"的剩余额度（预算由 waitForPlan 与 urgent 路径累计）。 */
    public long remainingTurnBudget() {
        return Math.max(0, settings.waitPerTurnMs() - checkpoints.turnBudgetUsedMs());
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


    private void rotateAndAnchor(String key, int turn, String side, String anchor, long recordSeq) {
        String handoff = segmentKey.isEmpty() ? "" : handoffSummary();
        segmentKey = key;
        checkpoints.markWatermark(recordSeq);
        checkpoints.resetForNewSegment();
        history.startSegment(SystemMessage.from(settings.pathPlanning()
                ? PlannerPrompts.PLANNER_SYSTEM
                : PlannerPrompts.ADVISOR_SYSTEM),
                packer.plannerStaticMaterial(), handoff, anchorRequest(turn, side, anchor));
        checkpointCount.set(0);
        checkpoints.resetTurnBudget();
        planWaitExhausted.clear();
        synchronized (pendingEvents) {
            pendingEvents.clear();
        }
        runCheckpoint("anchor");
    }

    /** 锚点请求：按模式/回合方选模板；无可沿用计划时明确禁止 keep（防"keep 无计划"空转一轮）。 */
    private String anchorRequest(int turn, String side, String anchor) {
        boolean oppTurn = side.contains("对方");
        boolean pathPlanning = settings.pathPlanning();
        String anchorRequest = pathPlanning
                ? (oppTurn
                ? PlannerPrompts.ANCHOR_OPP_TEMPLATE.formatted(turn, anchor)
                : PlannerPrompts.ANCHOR_TEMPLATE.formatted(turn, side, anchor))
                : (oppTurn
                ? PlannerPrompts.ADVISOR_ANCHOR_OPP_TEMPLATE.formatted(turn, anchor)
                : PlannerPrompts.ADVISOR_ANCHOR_TEMPLATE.formatted(turn, side, anchor));
        if (keeper.isEmpty()) {
            anchorRequest = anchorRequest
                    + "\n注意：这是本段首次请求，当前没有任何可沿用的建议/计划"
                    + "——禁止回复 {\"keep\": true}，请直接输出完整的建议/计划 JSON。";
        }
        return anchorRequest;
    }

    /**
     * 顾问模式下 events checkpoint 的每回合上限：顾问建议只有 goal/战术思想、没有 steps 可修订，
     * 实机一把连发 5 个 checkpoint 全部回 keep（每次 ~22k 字白烧），只保留一次确认。
     * 路径规划模式的计划有 steps，事件可能让后续步失效，维持原配额。
     */
    private static final int ADVISOR_EVENTS_CHECKPOINTS_PER_TURN = 1;

    private int eventsCheckpointQuota() {
        return settings.pathPlanning()
                ? settings.maxCheckpointsPerTurn()
                : ADVISOR_EVENTS_CHECKPOINTS_PER_TURN;
    }

    /**
     * checkpoint：发送事件批并请求 keep/新计划（异步）。
     */
    public void flushEvents() {
        if (segmentKey.isEmpty()) {
            return;
        }
        if (!checkpoints.quotaAvailable(eventsCheckpointQuota(),
                checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
            return; // 成本闸：本回合后续事件继续累积，不再调用
        }
        scheduleEventCheckpoint();
    }

    private void scheduleEventCheckpoint() {
        if (!checkpoints.tryQueue()) {
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
                    events = new ArrayList<>(pendingEvents);
                    pendingEvents.clear();
                }
                // 记录行补"对方做了什么"：pendingEvents 只有我方应答，对手的动作
                // （上场/盖放/发动/LP 变动）只能从对局记录的增量里来
                List<String> recordLines = timeline == null ? List.of()
                        : packer.timelineLines(timeline.since(checkpoints.watermark()));
                checkpoints.markWatermark(timeline == null ? 0 : timeline.latestSeq());
                if (events.isEmpty() && recordLines.isEmpty()) {
                    return;
                }
                if (!checkpoints.quotaAvailable(eventsCheckpointQuota(),
                        checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
                    return;
                }
                checkpoints.incrementSent();
                List<String> batch = new ArrayList<>(events);
                batch.addAll(recordLines);
                history.add(UserMessage.from(PlannerPrompts.EVENT_BATCH_TEMPLATE.formatted(
                        String.join("\n", batch))));
                runCheckpoint("events");
            } finally {
                checkpoints.release();
                // 自排空：检查点期间又攒了新事件 → 立刻再发一批（仍受配额与间隔约束）
                if (!segment.equals(segmentKey)) {
                    return;
                }
                boolean more;
                synchronized (pendingEvents) {
                    more = !pendingEvents.isEmpty();
                }
                if (checkpoints.shouldSelfDrain(more, eventsCheckpointQuota(),
                        checkpointCount.get(), settings.maxCheckpointsPerTurn())) {
                    try {
                        Thread.sleep(EventCheckpoints.GAP_MS);
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
            List<ChatMessage> prompt = history.snapshot();
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
            history.addAssistant(raw);
            checkpointCount.incrementAndGet();
            if (isKeep(raw)) {
                if (keeper.isEmpty()) {
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
                keeper.carryOver(turnNumberOfSegment()).ifPresent(carried -> {
                    log.info("规划器回 keep（{}）：第 {} 回合的计划续到第 {} 回合（steps={} branches={} 置信 {}）",
                            kind, carried.fromTurn(), turnNumberOfSegment(), carried.steps(),
                            carried.branches(), String.format(Locale.ROOT, "%.2f", carried.confidence()));
                    planEvent("PLAN_CARRIED_OVER", Map.of(
                            "checkpoint", String.valueOf(kind),
                            "planId", carried.planId(),
                            "fromTurn", carried.fromTurn(),
                            "turn", turnNumberOfSegment(),
                            "steps", carried.steps(),
                            "confidence", carried.confidence()));
                });
                return;
            }
            PlanTreeParser.Result parsed = PlanTreeParser.parseDetailed(raw, this::isKnownCard);
            if (parsed != null && parsed.plan() != null) {
                PlanTreeParser.ParsedPlan plan = parsed.plan();
                String planId = "P" + segmentKey + "#" + checkpointCount.get();
                keeper.publish(plan, planId, turnNumberOfSegment());
                log.info("规划器产出计划 {}（{}ms，steps={} branches={} 丢弃非法分支步={}）", planId,
                        elapsed, plan.steps().size(), plan.branches().size(), plan.droppedSteps());
                planEvent("PLAN_PUBLISHED",
                        keeper.publishedPayload(planId, plan, kind, elapsed));
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
        return keeper.current()
                .map(plan -> "- 最近思路：" + plan.thinking() + "\n- 目标：" + plan.goal()
                        + "\n- 计划完成度：" + plan.cursor() + "/" + plan.steps().size())
                .orElse("（上一段无有效计划）");
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
}
