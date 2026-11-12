package com.github.coldgust.ygoagent.pipeline.judge;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DecisionMemory;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.DecisionTools;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionExplanation;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.PathKind;
import com.github.coldgust.ygoagent.model.Timing;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TurnMemory;
import com.github.coldgust.ygoagent.pipeline.plan.CandidateIntents;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * ④ 决策器判断：投机双路——直答（单轮 LLM 带候选白名单，可调工具）与规划器抢答竞速，
 * 先到先得；planner 信息全则优先（§2.2.5）。
 * <p>两路都在别的线程里跑，失败原因收集进 {@code missReasons} 再并回主线程；
 * 拿到草案后只做"能不能执行"的校验（候选必须真实存在于请求内），不做"该不该这么打"的改判。
 */
public final class LlmJudge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    private final ChatClient chat;
    private final ContextPackBuilder packer;
    private final DecisionTools tools;
    private final PipelineSettings settings;
    private final PlannerSession planner;
    private final TurnMemory memory;
    private final VerboseLog trace;
    private final ExecutorService judgePool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ygo-judge");
        t.setDaemon(true);
        return t;
    });

    public LlmJudge(ChatClient chat, ContextPackBuilder packer, DecisionTools tools,
                    PipelineSettings settings, PlannerSession planner, TurnMemory memory,
                    VerboseLog trace) {
        this.chat = chat;
        this.packer = packer;
        this.tools = tools == null ? DecisionTools.NONE : tools;
        this.settings = settings;
        this.planner = planner;
        this.memory = memory;
        this.trace = trace;
    }

    private record Drafted(DuelDecision decision, ZoneRef zone) {
    }

    /** ④ 主入口：跑双路竞速；拿到结果即采纳（记录位置线索）。空 = 本级失败，落 ⑤。 */
    public Optional<DuelDecision> judge(DecisionPoint point, DecisionDeadline deadline,
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
        TurnPlan planForJudge = planner.currentPlan().orElse(null);
        // 这一手是在执行哪个效果的哪一步：从属决策点（选卡/选位置/选表示形式）全靠它才有语义
        String effectScope = memory.effectScopeFor(point.turn())
                .map(scope -> packer.describeEffect(scope.card(), scope.description()))
                .orElse("");
        // 这个决策点之前"已经做过什么 + 上一手怎么想的"（单轮调用没有对话历史，必须显式给）
        DecisionMemory memoryPack =
                DecisionMemory.of(effectScope, memory.turnActions(), memory.lastIntent());
        CompletableFuture<Optional<Drafted>> judgeFuture =
                CompletableFuture.supplyAsync(() -> {
                    String pack = packer.decisionPack(point, planForJudge, memoryPack);
                    List<ChatMessage> messages = List.of(
                            SystemMessage.from(JudgePrompts.SYSTEM),
                            UserMessage.from(pack));
                    ChatClient.ChatOptions options = ChatClient.ChatOptions.decision(
                                    settings.judgeMaxTokens(),
                                    Duration.ofMillis(Math.min(settings.judgeTimeoutMs(), deadlineMs)))
                            // 思考强度走配置（LLM_REASONING_DECISION）；留空 = 沿用决策档默认（关思考）
                            .withReasoning(settings.reasoningDecision());
                    // 工具按需注册：规则 / 连招 / 查卡 / 历史对局，模型自己决定要不要调用（工具轮对上层透明）
                    List<Object> toolList = tools.providers();
                    String raw;
                    try (var ignored = trace.scope("决策器判断 T" + point.turn() + " " + point.kind())) {
                        raw = toolList.isEmpty() ? chat.complete(messages, options)
                                : chat.completeWithTools(messages, options, toolList);
                    }
                    Optional<ChoiceDraft> draft = ChoiceDraft.parse(raw);
                    if (draft.isEmpty()) {
                        notes.add("直答输出不可解析：" + snippet(raw));
                        return Optional.<Drafted>empty();
                    }
                    Optional<Drafted> built = buildFromDraft(point, draft.get(), "直答", null);
                    if (built.isPresent() || draft.get().choice() == null
                            || point.candidate(draft.get().choice()).isPresent()) {
                        return built.or(() -> {
                            notes.add("直答无法映射成意图：" + draft.get().choice().value());
                            return Optional.<Drafted>empty();
                        });
                    }
                    notes.add("直答选择越界：" + draft.get().choice().value()
                            + "（候选 " + point.candidates().size() + " 个），已要求重答");
                    List<ChatMessage> retryMessages = new ArrayList<>(messages);
                    retryMessages.add(UserMessage.from(
                            "你的 choice=" + draft.get().choice().value() + " 不在候选集内。"
                                    + "本决策点可选编号：" + candidateCodes(point)
                                    + "（本请求没有 C0）。请只输出 JSON，choice 必须是其中之一。"));
                    ChatClient.ChatOptions retryOptions = ChatClient.ChatOptions.decision(
                                    settings.judgeMaxTokens(),
                                    Duration.ofMillis(Math.max(600, options.timeout().toMillis() / 2)))
                            .withReasoning(settings.reasoningDecision());
                    String retried;
                    try (var ignored = trace.scope("决策器判断重答 T" + point.turn() + " " + point.kind())) {
                        retried = toolList.isEmpty() ? chat.complete(retryMessages, retryOptions)
                                : chat.completeWithTools(retryMessages, retryOptions, toolList);
                    }
                    Optional<ChoiceDraft> second = ChoiceDraft.parse(retried);
                    if (second.isEmpty()) {
                        notes.add("重答仍不可解析：" + snippet(retried));
                        return Optional.<Drafted>empty();
                    }
                    return buildFromDraft(point, second.get(), "直答·重答", notes);
                }, judgePool)
                        .exceptionally(ex -> {
                            log.info("决策器判断调用失败: {}", String.valueOf(ex));
                            notes.add("直答调用失败：" + rootCause(ex));
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
                break; // planner 明确无结果 → 只等 直答
            }
            if (judgeFuture.isDone()) {
                Optional<Drafted> d = joinQuietly(judgeFuture);
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
            judgeFuture.cancel(true);
            notes.add("直答超时：等待阶段已耗尽 " + deadlineMs + "ms");
            missReasons.addAll(notes);
            return Optional.empty();
        }
        try {
            Optional<DuelDecision> decided = judgeFuture.get(remaining, TimeUnit.MILLISECONDS).map(this::accept);
            missReasons.addAll(notes);
            return decided;
        } catch (TimeoutException e) {
            judgeFuture.cancel(true);
            notes.add("直答超时：" + (System.currentTimeMillis() - start) + "ms 内未返回");
            missReasons.addAll(notes);
            return Optional.empty();
        } catch (Exception e) {
            notes.add("直答异常：" + rootCause(e));
            missReasons.addAll(notes);
            return Optional.empty();
        }
    }

    private DuelDecision accept(Drafted drafted) {
        memory.recordDecisionZone(drafted.zone());
        return drafted.decision();
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

    /**
     * @param notes 失败原因收集器（可为 null）；"选择越界/无法映射成意图"这类静默失败必须留下痕迹，
     *              否则上层只会看到一句笼统的"校验失败"。
     */
    private Optional<Drafted> buildFromDraft(DecisionPoint point, ChoiceDraft draft,
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

    @Override
    public void close() {
        judgePool.shutdownNow();
    }
}
