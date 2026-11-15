package com.github.coldgust.ygoagent.pipeline.judge;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelRecordFeed;
import com.github.coldgust.ygoagent.llm.LlmChatException;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;
import com.github.coldgust.ygoagent.llm.service.JudgeAgent;
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
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorKeeper;
import com.github.coldgust.ygoagent.pipeline.plan.CandidateIntents;
import com.github.coldgust.ygoagent.planner.PlannerRuntime;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ② 决策器判断：带记忆的持续对话（docs/planner-judge-memory-redesign.md §7）——
 * 记录流拉取（入史）→ 快照 stage（不入史）→ 薄请求行 → （越界时）更正行 → finally drop 快照。
 * 拿到草案后只做"能不能执行"的校验（候选必须真实存在于请求内），不做"该不该这么打"的改判。
 * 抢答双路竞速已随顾问定位定稿删除：④ 失败直接落 ③ 兜底。
 * <p>调用失败（{@link LlmChatException}）与输出不可解析分开记因：前者可能是端点故障，
 * 后者只是模型行为问题。
 */
public final class LlmJudge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    private final JudgeAgent judgeAgent;
    private final ContextPackBuilder packer;
    private final PlannerRuntime planner;
    private final TurnMemory memory;
    private final DuelChatMemory judgeMemory;
    private final DuelRecordFeed feed;
    private final VerboseLog trace;
    private final ExecutorService judgePool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ygo-judge");
        t.setDaemon(true);
        return t;
    });

    public LlmJudge(JudgeAgent judgeAgent, ContextPackBuilder packer, PipelineSettings settings,
                    PlannerRuntime planner, TurnMemory memory,
                    DuelChatMemory judgeMemory, DuelRecordFeed feed, VerboseLog trace) {
        this.judgeAgent = judgeAgent;
        this.packer = packer;
        this.planner = planner;
        this.memory = memory;
        this.judgeMemory = judgeMemory;
        this.feed = feed;
        this.trace = trace;
    }

    private record Drafted(DuelDecision decision, ZoneRef zone) {
    }

    /** ② 主入口：记录流入史 + 快照一次性注入，单路调用；空 = 本级失败，落 ③。 */
    public Optional<DuelDecision> judge(DecisionPoint point, DecisionDeadline deadline,
                                        List<String> missReasons) {
        long deadlineMs = Math.max(0, deadline.remainingMillis() - deadline.safetyMillis());
        if (deadlineMs < 500) {
            missReasons.add("升级跳过：预算只剩 " + deadlineMs + "ms（<500ms）");
            return Optional.empty();
        }
        // 失败原因收集（ArrayList 不是线程安全的，先收集再并回主线程）；细分成
        // "调用失败 / 输出不可解析 / 选择越界 / 超时"，否则查不出原因。
        Queue<String> notes = new ConcurrentLinkedQueue<>();
        AdvisorKeeper.Snapshot advice = planner.currentAdvice().orElse(null);
        // 这一手是在执行哪个效果的哪一步：从属决策点（选卡/选位置/选表示形式）全靠它才有语义
        String effectScope = memory.effectScopeFor(point.turn())
                .map(scope -> packer.describeEffect(scope.card(), scope.description()))
                .orElse("");
        CompletableFuture<Optional<Drafted>> judgeFuture = CompletableFuture.supplyAsync(() -> {
            // 记录流先入史（回合收尾摘要 + 对手/系统增量，入 memory），快照随后 stage——
            // 静态资料与记录已在 memory（pinned + 追加流），每手只发薄请求行
            feed.fillBeforeCall(judgeMemory, point.turn());
            try {
                judgeMemory.stageOnce(UserMessage.from(
                        packer.judgeSnapshot(point, advice, effectScope)));
                // 工具按需注册：规则 / 连招 / 查卡 / 历史对局，模型自己决定要不要调用（工具轮对上层透明）
                JudgeAnswer answer;
                try (var ignored = trace.scope("决策器判断 T" + point.turn() + " " + point.kind())) {
                    answer = judgeAgent.decide(packer.requestHeadline(point),
                            chainSection(point), candidatesSection(point), effectSection(point));
                } catch (RuntimeException e) {
                    notes.add(callFailure(e) ? "判断调用失败：" + rootCause(e)
                            : "判断输出不可解析：" + rootCause(e));
                    return Optional.<Drafted>empty();
                }
                maybeRequestReplan(point, answer);
                Optional<Drafted> built = buildFromDraft(point, answer, "判断", null);
                if (built.isPresent() || isNoneAnswer(answer)
                        || (answer.candidateId() != null
                            && point.candidate(answer.candidateId()).isPresent())) {
                    return built.or(() -> {
                        notes.add("判断无法映射成意图：" + answer.choice());
                        return Optional.<Drafted>empty();
                    });
                }
                notes.add("判断选择越界：" + answer.choice()
                        + "（候选 " + point.candidates().size() + " 个），已要求重答");
                JudgeAnswer second;
                try (var ignored = trace.scope("决策器判断重答 T" + point.turn() + " " + point.kind())) {
                    second = judgeAgent.retry(answer.choice(), candidateCodes(point));
                } catch (RuntimeException e) {
                    notes.add(callFailure(e) ? "重答调用失败：" + rootCause(e)
                            : "重答仍不可解析：" + rootCause(e));
                    return Optional.<Drafted>empty();
                }
                maybeRequestReplan(point, second);
                return buildFromDraft(point, second, "判断·重答", notes);
            } finally {
                // 快照只用一次（含重答）；入史的只有请求行/更正行/回复/工具对
                judgeMemory.dropEphemeral();
            }
        }, judgePool)
                .exceptionally(ex -> {
                    log.info("决策器判断调用失败: {}", String.valueOf(ex));
                    notes.add("判断调用失败：" + rootCause(ex));
                    return Optional.empty();
                });

        try {
            Optional<DuelDecision> decided = judgeFuture.get(deadlineMs, TimeUnit.MILLISECONDS).map(this::accept);
            missReasons.addAll(notes);
            return decided;
        } catch (TimeoutException e) {
            judgeFuture.cancel(true);
            notes.add("判断超时：" + deadlineMs + "ms 内未返回");
            missReasons.addAll(notes);
            return Optional.empty();
        } catch (Exception e) {
            notes.add("判断异常：" + rootCause(e));
            missReasons.addAll(notes);
            return Optional.empty();
        }
    }

    private DuelDecision accept(Drafted drafted) {
        memory.recordDecisionZone(drafted.zone());
        return drafted.decision();
    }

    /**
     * 决策器的重规划判决（needReplan=true，2026-11-14 用户指令）：
     * <ol>
     *   <li>重置"本回合已等过建议"标记——下一次决策前按配置的首次等待时长
     *       （firstAdviceWaitMs）<b>再</b>等一次新规划；</li>
     *   <li>{@link PlannerRuntime#requestReplan}：发起重规划（每回合一次配额），
     *       原因原样转达规划器；配额闸通过时旧建议即刻作废——
     *       新规划没来得及发布，决策器就不带旧规划内容（快照渲染的 [规划器情报] 段缺席）。</li>
     * </ol>
     * 本手决策不受影响：choice 已照常给出，重规划只改变后续的规划供给。
     */
    private void maybeRequestReplan(DecisionPoint point, JudgeAnswer answer) {
        if (answer == null || !answer.needReplanOr(false)) {
            return;
        }
        memory.clearWaitedForAdvice(point.turn());
        planner.requestReplan(point, answer.replanReason());
    }

    /**
     * 请求行分段变量（入 memory）：局面与卡面在快照里，但<b>这一手在判什么</b>必须随请求入史——
     * 抬头给回合/阶段/性质，连锁链（谁在第几环发动了什么效果）与候选的条款正文都写进来
     * （链、候选、效果三段，见下方各渲染器；骨架固定句在 {@code JudgeAi} 模板注解里）。
     *
     * <p>2026-11-14 实测三条：① 只写"连锁响应"时，记忆里看不出当时在响应对手的什么效果，
     * 模型复盘时只能靠猜；② 候选只有卡名（"C1 发动 连刀、C2 发动 连刀"）时，
     * 同一张多效果卡的选项长得一模一样，连"发动的是哪一条"都判断不了；
     * ③ 链上没东西时曾写成"连锁：空"——游戏王里没有这种说法，那是"此时可发动效果"的
     * 优先权窗口，抬头按玩家的话写（{@link ContextPackBuilder#requestHeadline}）。
     */
    private String chainSection(DecisionPoint point) {
        return section(packer.compactChain(point));
    }

    /** "候选 N 项："引导句绑定运行时计数，留在段渲染器（模板没有条件指令）。 */
    private String candidatesSection(DecisionPoint point) {
        String list = packer.compactCandidates(point);
        return list.isEmpty() ? "" : "候选 " + point.candidates().size() + " 项：" + list + "\n";
    }

    /** "候选效果："引导句同理留在段渲染器（与"候选 N 项："对称）。 */
    private String effectSection(DecisionPoint point) {
        String digest = packer.compactEffectDigest(point);
        return digest.isEmpty() ? "" : "候选效果：" + digest + "\n";
    }

    /** 段约定：空段 = ""（模板占位符要求全量变量，缺了框架会抛错）；非空段以 "\n" 收尾。 */
    private static String section(String text) {
        return text.isEmpty() ? "" : text + "\n";
    }

    private static String candidateCodes(DecisionPoint point) {
        return point.candidates().stream()
                .map(c -> c.id().value())
                .distinct()
                .collect(Collectors.joining("、"));
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

    private static boolean callFailure(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof LlmChatException) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /**
     * @param notes 失败原因收集器（可为 null）；"选择越界/无法映射成意图"这类静默失败必须留下痕迹，
     *              否则上层只会看到一句笼统的"校验失败"。
     */
    private Optional<Drafted> buildFromDraft(DecisionPoint point, JudgeAnswer answer,
                                             String source, Queue<String> notes) {
        CandidateId id = answer.candidateId();
        // 兼容：模型偶尔仍会写 C0/NONE/PASS/弃权（旧提示词的习惯）。此时把它当成本请求的弃权候选，
        // 而不是判越界——弃权本身是合法动作，不该因为编号写法不同就丢掉整条决策。
        if (id == null && isNoneAnswer(answer)) {
            id = CandidateId.of(0);
        }
        if (id != null && isNoneChoice(id)) {
            Optional<ChoiceIntent> none = point.candidatesOf(Candidate.DeclineC.class).stream()
                    .findFirst()
                    .flatMap(decline -> CandidateIntents.toIntent(point, decline));
            if (none.isEmpty()) {
                none = noneIntent(point);
            }
            if (none.isPresent()) {
                return Optional.of(new Drafted(new DuelDecision(none.get(), PathKind.LLM_CALL,
                        answer.confidenceOr(0.3),
                        DecisionExplanation.of(withRationale(source, answer.rationale())),
                        Timing.ZERO), answer.zoneRef()));
            }
        }
        Optional<Candidate> candidate = id == null ? Optional.empty() : point.candidate(id);
        if (candidate.isEmpty()) {
            log.info("{} 选择越界/不可解析: {}（候选 {} 个）", source, answer.choice(),
                    point.candidates().size());
            if (notes != null) {
                notes.add(source + " 选择越界：" + answer.choice()
                        + "（候选 " + point.candidates().size() + " 个）");
            }
            return Optional.empty();
        }
        Optional<ChoiceIntent> intent = CandidateIntents.toIntent(point, candidate.get());
        if (intent.isEmpty()) {
            if (notes != null) {
                notes.add(source + " 无法映射成意图：" + id.value()
                        + "（" + candidate.get().getClass().getSimpleName() + "）");
            }
            return Optional.empty();
        }
        String rationale = answer.rationale() == null ? "" : answer.rationale();
        return Optional.of(new Drafted(new DuelDecision(intent.get(), PathKind.LLM_CALL,
                answer.confidenceOr(0.3),
                DecisionExplanation.of(withRationale(source, rationale)), Timing.ZERO),
                answer.zoneRef()));
    }

    /** 选择字段没有合法编号时的弃权写法（C0/NONE/PASS/弃权…）。 */
    private static boolean isNoneAnswer(JudgeAnswer answer) {
        String v = answer.choice() == null ? "" : answer.choice().trim().toUpperCase(Locale.ROOT);
        return "C0".equals(v) || "0".equals(v) || "NONE".equals(v) || "PASS".equals(v)
                || "不响应".equals(v) || "弃权".equals(v) || "放弃优先权".equals(v)
                || "取消".equals(v);
    }

    /** CandidateId.of 生成的值只有 "C<n>" 形态，弃权候选即 C0。 */
    private static boolean isNoneChoice(CandidateId id) {
        return "C0".equals(id.value());
    }

    /** 来源 + 模型理由 → 一句解释；理由为空时只留来源，不留悬空的分隔符。 */
    private static String withRationale(String source, String rationale) {
        String clean = rationale == null ? "" : rationale.trim();
        return clean.isEmpty() ? source : source + "：" + clean;
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
