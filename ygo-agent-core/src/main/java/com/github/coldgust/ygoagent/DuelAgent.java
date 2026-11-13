package com.github.coldgust.ygoagent;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.context.RuleDigest;
import com.github.coldgust.ygoagent.context.DuelTimeline;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.knowledge.ComboReferenceText;
import com.github.coldgust.ygoagent.knowledge.JsonlCardFacts;
import com.github.coldgust.ygoagent.knowledge.RecordedComboSource;
import com.github.coldgust.ygoagent.llm.CardTool;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.ComboTool;
import com.github.coldgust.ygoagent.llm.DecisionTools;
import com.github.coldgust.ygoagent.llm.DuelRecallTool;
import com.github.coldgust.ygoagent.llm.LightRagSettings;
import com.github.coldgust.ygoagent.llm.RulebookTool;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.DecisionDeadline;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.pipeline.DecisionEngine;
import com.github.coldgust.ygoagent.pipeline.DecisionPipeline;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.fallback.RuleBasedEngine;
import com.github.coldgust.ygoagent.pipeline.fallback.SimpleArbiter;
import com.github.coldgust.ygoagent.pipeline.fallback.TacticalPlannerImpl;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicyImpl;
import com.github.coldgust.ygoagent.planner.PlannerSession;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * ygo-agent-core 的门面：一次对局的决策引擎（五级流水 + 后台规划器）。
 *
 * <p>宿主（CLI / server）按局调用 {@link #open}，拿到一个现成的 {@link DecisionEngine}；
 * 全部配置经参数传入（core 不读环境变量、无静态单例），对局结束调用 {@link #close} 释放后台线程。
 * <p>{@code settings} 为 null 或未启用时退化为纯规则链（⑤ 兜底链独立成引擎），其余情况为完整五级流水。
 */
public final class DuelAgent implements DecisionEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DuelAgent.class);

    private final DecisionEngine engine;
    private final CardFacts facts;

    private DuelAgent(DecisionEngine engine, CardFacts facts) {
        this.engine = engine;
        this.facts = facts;
    }

    /**
     * 组装一次对局的决策引擎。
     *
     * @param settings 规划器/流水设置；null 或 enabled=false → 纯规则链（无 LLM、无后台线程）
     * @param config   本局的上下文与轨迹配置（见 {@link Config}）
     * @param verbose  全链路日志（进程级；贯穿 chat 埋点、规划器与流水）
     * @throws UncheckedIOException 卡牌事实文件读取失败（fail-fast，对局不该在没卡面的情况下开打）
     */
    public static DuelAgent open(PipelineSettings settings, Config config, VerboseLog verbose) {
        VerboseLog log0 = verbose == null ? new VerboseLog(VerboseLogSettings.off()) : verbose;
        DecisionTraceWriter traceWriter = config.traceWriter() == null
                ? DecisionTraceWriter.NOOP : config.traceWriter();
        RuleBasedEngine baseEngine = new RuleBasedEngine(
                new ReflexPolicyImpl(), new TacticalPlannerImpl(), new SimpleArbiter(), traceWriter);
        boolean llm = settings != null && settings.enabled();
        if (!llm) {
            return new DuelAgent(baseEngine, config.facts());
        }
        CardFacts facts = config.facts();
        if (facts == null) {
            try {
                facts = JsonlCardFacts.load(settings.cardRagPath());
            } catch (IOException e) {
                throw new UncheckedIOException("卡牌事实加载失败: " + settings.cardRagPath(), e);
            }
        }
        String comboReference = ComboReferenceText.render(config.combos(), config.archetype(),
                config.comboContextLimit());
        ContextPackBuilder packer = ContextPackBuilder.builder(facts, new DuelMathService())
                .deckStrategy(config.deckStrategy())
                .ruleDigest(RuleDigest.TEXT)
                .archetype(config.archetype())
                .advisory(!settings.pathPlanning())
                // 己方卡组全清单：规划器据此判断能检索/特召什么，不必现查
                .deckPasscodes(config.deckPasscodes())
                .comboReference(comboReference)
                .timeline(config.timeline())
                .build();
        if (packer.hasDeckStrategy()) {
            log.info("已注入卡组思路（{} 字），将作为规划器与决策器判断（④）的重点参考",
                    config.deckStrategy().length());
        }
        if (packer.hasComboReference()) {
            log.info("已注入实战连招参考（主题 {}，{} 字）：PG combo 库最近的真实录入",
                    config.archetype(), comboReference.length());
        }
        if (packer.hasRuleDigest()) {
            log.info("已预置规则速查（浓缩版）；精确条文可由模型自主调用 search_rulebook 检索");
        }
        // 规则检索工具：LightRAG 不可达时工具调用会失败并回填错误，模型仍可基于速查继续
        RulebookTool rulebook = new RulebookTool(config.lightRag(), "mix", 8);
        ChatClient chat = ChatClient.openAiCompatible(settings.endpoint(), log0);
        // 连招查询工具：按主题/关键卡查 PG 实战录入（主要用于推断对手卡组后参考）
        ComboTool comboTool = config.combos() != null && config.combos().available()
                ? new ComboTool(config.combos(), config.archetype())
                : null;
        // 查卡工具：规划器也要有——判断"这张卡和那张能不能配合"必须有卡面原文，
        // 只给卡名等于让它凭训练记忆猜效果（实测就会退化成逐张单卡评价）。
        CardTool cardTool = new CardTool(facts);
        // 规划器的工具集：规则 + 查卡 + 连招（不含历史对局检索，那是执行器的事）
        DecisionTools plannerTools = new DecisionTools(rulebook, comboTool, cardTool);
        PlannerSession planner = new PlannerSession(
                chat, packer, facts, settings, traceWriter, plannerTools,
                config.timeline(), log0);
        DuelRecallTool duelRecall = new DuelRecallTool(config.lightRag(), "mix", 4);
        DecisionTools decisionTools = new DecisionTools(rulebook, comboTool, cardTool, duelRecall);
        DecisionPipeline pipeline = new DecisionPipeline(
                new ReflexPolicyImpl(), planner, packer, chat, settings, facts,
                baseEngine, traceWriter, decisionTools, log0);
        log.info("五级流水已启用（model={} wait-decision={}ms wait-turn={}ms 卡库 {}）",
                settings.model(), settings.waitPerDecisionMs(),
                settings.waitPerTurnMs(), facts.knownCount());
        return new DuelAgent(pipeline, facts);
    }

    /** 本局实际用的卡牌事实（宿主可能要复用，如把候选编号翻译成卡名）。纯规则链且未注入时为 null。 */
    public CardFacts cardFacts() {
        return facts;
    }

    @Override
    public DuelDecision decide(DecisionPoint point, DecisionDeadline deadline) {
        return engine.decide(point, deadline);
    }

    @Override
    public void close() {
        if (engine instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("决策引擎关闭失败", e);
            }
        }
    }

    /**
     * 本局上下文配置。
     *
     * @param facts             卡牌事实；null → LLM 启用时按 {@code settings.cardRagPath()} 加载
     * @param traceWriter       决策轨迹落点（JSONL/SSE/日志的扇出由宿主组好传入）；null = NOOP
     * @param archetype         卡组主题（combo 注入与连招检索的键；可空）
     * @param deckStrategy      用户手写的卡组打法思路（可空）
     * @param deckPasscodes     己方卡组全清单（含额外卡组）
     * @param combos            PG 实战连招数据源（可空 = 无连招注入与检索）
     * @param comboContextLimit 连招注入的录入条数上限
     * @param lightRag          LightRAG 连接（规则速查/历史对局检索）
     * @param timeline          决斗过程记录端口（可空 = 不注入记录段）；宿主用
     *                          {@code session.duelLog()} 的适配器提供
     */
    public record Config(CardFacts facts,
                         DecisionTraceWriter traceWriter,
                         String archetype,
                         String deckStrategy,
                         List<Long> deckPasscodes,
                         RecordedComboSource combos,
                         int comboContextLimit,
                         LightRagSettings lightRag,
                         DuelTimeline timeline) {
    }

}
