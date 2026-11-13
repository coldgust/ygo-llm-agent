package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.plan.PlanStep;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.pipeline.plan.TurnPlan;
import com.github.coldgust.ygoagent.context.CardText;
import com.github.coldgust.ygoagent.context.DecisionMemory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.knowledge.CardFact;

/**
 * ContextPack 组装器（v6 §3.2/§3.4）：确定性代码把局面、卡面、数学算好，
 * 组装为规划器锚点与执行器 直答 的上下文。LLM 缺什么就供给什么，供给的都是数据。
 *
 * <p>渲染分三块协作：资料段（{@link AnchorSections}）、局面段（{@link BoardSections}）、
 * 候选段（{@link CandidateSections}）；本类负责装配与决策包特有的段落
 * （计划 / 本回合已做 / 当前效果 / 表示形式）。
 */
public final class ContextPackBuilder {

    private final CardFacts facts;
    /** 卡面文本的唯一出口：名字/条款/本次发动的是哪一条，全走它。 */
    private final CardText cardText;
    private final DuelMathService math;
    private final String deckStrategy;
    private final String ruleDigest;
    private final String selfArchetype;
    private final String comboReference;
    /** 顾问模式：计划只有战术建议、没有可执行步骤（{@code PLANNER_PATH_PLANNING=false}）。 */
    private final boolean advisoryPlan;
    /** 己方卡组清单（主+额外，去重）。null/空 = 不注入 [卡组] 段。 */
    private final List<Long> deckPasscodes;
    /** 决斗过程记录端口。null = 不注入记录段（单测与无记录的调用方）。 */
    private final DuelTimeline timeline;

    private final AnchorSections anchor;
    private final BoardSections board;
    private final CandidateSections candidateSections;
    private final DuelRecordSections recordSections;

    /** 最小构造：可选依赖（思路/规则速查/主题/卡组清单/连招/记录端口）全部缺席。 */
    public ContextPackBuilder(CardFacts facts, DuelMathService math) {
        this(facts, math, null, null, null, false, null, null, null);
    }

    /**
     * 可选依赖用 Builder 逐项声明（原来的 3–9 参望远镜重载已删除——
     * 每加一个依赖就加一条重载链的做法在 8 个构造器时已经失控）。
     */
    public static Builder builder(CardFacts facts, DuelMathService math) {
        return new Builder(facts, math);
    }

    /** 依赖收集器：字段与 {@link ContextPackBuilder} 的可选参数一一对应。 */
    public static final class Builder {
        private final CardFacts facts;
        private final DuelMathService math;
        private String deckStrategy;
        private String ruleDigest;
        private String selfArchetype;
        private boolean advisoryPlan;
        private List<Long> deckPasscodes = List.of();
        private String comboReference;
        private DuelTimeline timeline;

        private Builder(CardFacts facts, DuelMathService math) {
            this.facts = facts;
            this.math = math;
        }

        /** 用户为本卡组写的打法思路（可为空）。人给的策略知识，优先级高于通用战术常识。 */
        public Builder deckStrategy(String value) {
            this.deckStrategy = value;
            return this;
        }

        /** 规则速查文本（可为空）。预置后模型不必为常识反复检索；见 {@link RuleDigest}。 */
        public Builder ruleDigest(String value) {
            this.ruleDigest = value;
            return this;
        }

        /** 己方卡组主题（前端指定，可为空）。作为已知事实注入，避免模型猜自己的系列。 */
        public Builder archetype(String value) {
            this.selfArchetype = value;
            return this;
        }

        /** 顾问模式：规划器只产战术建议、不产可执行步骤（{@code PLANNER_PATH_PLANNING=false}）。 */
        public Builder advisory(boolean value) {
            this.advisoryPlan = value;
            return this;
        }

        /** 己方卡组清单（主+额外）。给全了规划器才知道"能检索到什么、额外能出什么"。 */
        public Builder deckPasscodes(List<Long> value) {
            this.deckPasscodes = value;
            return this;
        }

        /** 实战连招参考文本（PG combo 库按主题取最近几条，可为空）。 */
        public Builder comboReference(String value) {
            this.comboReference = value;
            return this;
        }

        /** 决斗过程记录端口（可为空 = 不注入记录段）。 */
        public Builder timeline(DuelTimeline value) {
            this.timeline = value;
            return this;
        }

        public ContextPackBuilder build() {
            return new ContextPackBuilder(facts, math, deckStrategy, ruleDigest, selfArchetype,
                    advisoryPlan, deckPasscodes, comboReference, timeline);
        }
    }

    private ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest, String selfArchetype, boolean advisoryPlan,
                              List<Long> deckPasscodes, String comboReference, DuelTimeline timeline) {
        this.facts = facts;
        this.cardText = new CardText(facts);
        this.math = math;
        this.deckStrategy = deckStrategy == null || deckStrategy.isBlank() ? null : deckStrategy.trim();
        this.ruleDigest = ruleDigest == null || ruleDigest.isBlank() ? null : ruleDigest.trim();
        this.selfArchetype = selfArchetype == null || selfArchetype.isBlank()
                ? null : selfArchetype.trim();
        this.advisoryPlan = advisoryPlan;
        this.comboReference = comboReference == null || comboReference.isBlank()
                ? null : comboReference.trim();
        this.deckPasscodes = deckPasscodes == null ? List.of()
                : List.copyOf(deckPasscodes);
        this.timeline = timeline;
        this.anchor = new AnchorSections(facts, this.deckStrategy, this.ruleDigest,
                this.selfArchetype, this.comboReference, this.deckPasscodes);
        this.board = new BoardSections(facts);
        this.candidateSections = new CandidateSections(facts, this.cardText);
        this.recordSections = new DuelRecordSections(facts, this.cardText);
    }

    public boolean hasRuleDigest() {
        return ruleDigest != null;
    }

    public boolean hasDeckStrategy() {
        return deckStrategy != null;
    }

    public boolean hasComboReference() {
        return comboReference != null;
    }

    public String anchorPack(DuelState state) {
        StringBuilder sb = new StringBuilder();
        anchor.coreSections(sb);
        sb.append(anchor.comboSection());
        sb.append(dynamicBoardPack(state));
        return sb.toString();
    }

    /**
     * 决策器静态资料段（整局不变）：卡组主题 / 卡组思路 / 规则速查。
     * 连招参考与 [卡组] 清单是规划器专属（回合级路线规划才用得上），决策器不注入。
     */
    public String judgeStaticMaterial() {
        StringBuilder sb = new StringBuilder();
        anchor.coreSections(sb);
        return sb.toString();
    }

    /**
     * 规划器静态资料段（整局不变）：核心资料 + [实战连招参考] + [卡组] 全清单。
     * 规划器负责回合级路线规划，需要连招库与整副卡组判断"能检索什么、额外能出什么"。
     * 由 PlannerSession 作为独立消息放在 history 最前——跨回合逐字节一致，
     * deepseek 服务端前缀缓存能在整局所有规划器调用上命中这部分。
     */
    public String plannerStaticMaterial() {
        StringBuilder body = new StringBuilder();
        anchor.coreSections(body);
        body.append(anchor.comboSection());
        body.append(anchor.deckSection());
        if (body.isEmpty()) {
            return ""; // 无任何静态资料（如离线单测）时不输出空壳
        }
        return "[整局资料] 以下内容在本局全程不变（主题 / 思路 / 连招参考 / 规则速查 / 卡组清单）；"
                + "局面与过程记录见后续消息。\n\n" + body;
    }

    /**
     * 动态局面段：局面行 / 双方手牌与场面 / 墓地·除外 / 场地槽位 / 约束计数。
     * 每次决策都可能变，不能进缓存前缀。
     */
    public String dynamicBoardPack(DuelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[局面] 第 ").append(state.turnNumber()).append(" 回合，")
                .append(state.isMyTurn() ? "我方回合" : "对方回合")
                .append("，阶段 ").append(state.phase())
                .append("；LP 我 ").append(state.self().lifePoints())
                .append(" / 对 ").append(state.opponent().lifePoints()).append("\n");
        sb.append("[我方手牌] ").append(board.handLine(state, PlayerSide.SELF)).append("\n");
        board.appendHandSpellUsability(sb, state);
        sb.append("[我方场面] ").append(board.fieldLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[我方墓地/除外] ").append(board.graveLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[对手场面] ").append(board.fieldLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手墓地（公开）] ").append(board.graveLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手手牌] ").append(state.opponent().handCount())
                .append(" 张（身份不可见）\n");
        sb.append(board.boardSlotsSection(state));
        // 只注入确定性计数；斩杀判定已停用（回合开始时必然"不可斩杀"，无信息量且误导）
        sb.append(math.constraintsSection(state, facts));
        return sb.toString();
    }

    /**
     * 规划器锚点（易变部分）：局面与决斗记录置顶（最重要的参考），其后卡面。
     * 静态资料不在其中——由 PlannerSession 作为独立消息放在 history 最前
     * （{@link #plannerStaticMaterial()}，跨回合一致，吃跨回合前缀缓存）。
     * 记录只含已结算回合（锚点时本回合刚开始，时间线由事件批增量补）。
     */
    public String plannerAnchorPack(DuelState state) {
        return dynamicBoardPack(state)
                + recordSections.recordOfClosedTurns(state.turnNumber(), timeline)
                + anchor.cardFactsSection(state);
    }

    /**
     * 回合记录前缀（已结算回合），决策器每次调用作为独立消息注入。
     * 回合内逐字节一致——上下文缓存的第二层。
     */
    public String recordOfClosedTurns(int currentTurn) {
        return recordSections.recordOfClosedTurns(currentTurn, timeline);
    }

    public String decisionPack(DecisionPoint point) {
        return decisionPack(point, null);
    }

    /**
     * 规划器事件批的记录行：条目由 PlannerSession 经端口增量拉取，本类只负责渲染。
     * 没有记录端口时返回空列表。
     */
    public List<String> timelineLines(List<DuelTimeline.Entry> entries) {
        return recordSections.lines(entries);
    }

    /**
     * 决策包（带计划版）。
     */
    public String decisionPack(DecisionPoint point, TurnPlan plan) {
        return decisionPack(point, plan, DecisionMemory.NONE);
    }

    /**
     * 决策包（带"记忆"版）：本回合已做的动作 + 上一手的判断 + 正在结算的效果。
     */
    public String decisionPack(DecisionPoint point, TurnPlan plan, DecisionMemory memory) {
        DecisionMemory remembered = memory == null ? DecisionMemory.NONE : memory;
        StringBuilder sb = new StringBuilder();
        // 静态资料（规则速查/卡组思路等）不再拼进决策包：调用方把它作为独立消息放在前面
        // （与 SYSTEM 一起构成稳定前缀吃上下文缓存），决策包只带动态内容
        sb.append(dynamicBoardPack(point.state())).append("\n");
        if (plan != null && (!plan.steps().isEmpty() || !plan.branches().isEmpty()
                || !Texts.isBlank(plan.goal()) || !Texts.isBlank(plan.thinking()))) {
            sb.append(planSection(point, plan)).append("\n");
        }
        // 只在对战阶做攻击选择时补攻击结算表（照样不给"可斩杀"结论）
        if (point.request() instanceof ChoiceRequest.BattlePhaseRequest) {
            sb.append(math.battleTableSection(point.state(), facts));
        }
        if (point.kind() == RequestKind.POSITION) {
            sb.append(positionSection());
        }
        String history = historySection(point, remembered, timeline != null);
        if (!history.isEmpty()) {
            sb.append(history).append("\n");
        }
        String record = recordSections.turnTimeline(point.turn(), point.state().isMyTurn(), timeline);
        if (!record.isEmpty()) {
            sb.append(record).append("\n");
        }
        String scope = effectScopeSection(point, remembered.effectScope());
        if (!scope.isEmpty()) {
            sb.append(scope).append("\n");
        }
        sb.append("[候选] 只能从下列编号中选择；id 在本决策点内有效：\n");
        sb.append(candidateSections.candidatesSection(point));
        return sb.toString();
    }

    /**
     * 把"发动了哪张卡的哪一条效果"渲染成一句人话（给 [当前效果] 用）。
     *
     * @param description 协议里的效果码（{@code 卡号<<4 | 序号}）；解不出条款时只给卡名
     */
    public String describeEffect(long passcode, long description) {
        return passcode <= 0 ? "" : cardText.firedSentence(passcode, description);
    }

    public String candidatesSection(DecisionPoint point) {
        return candidateSections.candidatesSection(point);
    }

    /** 本回合动作记录的条数上限（再多也只是噪声）。 */
    private static final int HISTORY_MAX_ACTIONS = 8;

    /**
     * 决策包的记忆段：有时间线时动作事实由 [本回合时间线] 承载（不重复列），
     * 这里只补时间线没有的"上一手的判断"；无时间线（离线/无记录注入）回退为完整动作列表。
     */
    private String historySection(DecisionPoint point, DecisionMemory memory, boolean hasTimeline) {
        if (memory == null || memory.turnActions().isEmpty() && memory.lastIntent().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!hasTimeline && !memory.turnActions().isEmpty()) {
            sb.append("[本回合已做] T").append(point.turn())
                    .append(" 你已经做过（动作是既成事实；这些动作的效果是否成立，看 [局面]）：\n");
            List<String> actions = memory.turnActions();
            int from = Math.max(0, actions.size() - HISTORY_MAX_ACTIONS);
            for (int i = from; i < actions.size(); i++) {
                sb.append("  ").append(i - from + 1).append(". ").append(actions.get(i)).append('\n');
            }
        }
        if (!memory.lastIntent().isEmpty()) {
            sb.append("  上一手的判断：").append(memory.lastIntent()).append('\n');
        }
        if (sb.isEmpty()) {
            return "";
        }
        sb.append("  要推翻上一手的判断可以，rationale 里说清「为什么改」；")
                .append("它与 [局面]/[本回合时间线] 相反时以后者为准。");
        return sb.toString();
    }

    /**
     * {@code [当前效果]} + {@code [本次请求]}：这个决策点是在执行哪个效果的哪一步。
     *
     * <p>只有"选卡/选位置/选表示形式"这类**从属决策**才需要：它们本身没有语义，
     * 语义全在父效果里。主阶段/连锁那种自带语义的请求不注入，省 token。
     */
    private String effectScopeSection(DecisionPoint point, String effectScope) {
        boolean subordinate = point.kind() == RequestKind.CARD_SELECT
                || point.kind() == RequestKind.UNSELECT
                || point.kind() == RequestKind.TRIBUTE_SELECT
                || point.kind() == RequestKind.SUM
                || point.kind() == RequestKind.PLACE
                || point.kind() == RequestKind.DISFIELD
                || point.kind() == RequestKind.POSITION;
        if (!subordinate) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!Texts.isBlank(effectScope)) {
            sb.append("[当前效果] 你刚才发动/正在结算的是：").append(effectScope.trim()).append("\n");
            sb.append("  下面这个决策点是它的**后续步骤**——你选的卡/格会被这个效果处理，")
                    .append("不是「要不要发动」的问题（除非候选里有弃权项）。\n");
        }
        String pool = candidatePool(point);
        if (pool != null) {
            sb.append("[本次请求] ").append(pool).append("\n");
        }
        return sb.toString();
    }

    /** 候选卡分布在哪些区域（"从场上选"和"从卡组选"是完全不同的判断）。 */
    private String candidatePool(DecisionPoint point) {
        if (!(point.request() instanceof ChoiceRequest.CardSelectRequest s)) {
            return null;
        }
        if (s.candidates().isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Integer> byZone = new LinkedHashMap<>();
        for (ChoiceRequest.CardChoice card : s.candidates()) {
            ZoneRef zone = card.zone();
            String key = zone == null
                    ? "未知区域"
                    : (zone.owner() == PlayerSide.SELF ? "我方" : "对方") + zoneKindName(zone.kind());
            byZone.merge(key, 1, Integer::sum);
        }
        StringJoiner zones = new StringJoiner("、");
        byZone.forEach((k, v) -> zones.add(k + " " + v + " 张"));
        return "从 " + point.candidates().size() + " 个合法候选中选 " + s.min() + "–" + s.max()
                + " 张（" + (s.cancelable() ? "可取消" : "**必选，没有弃权项**")
                + "）；候选分布：" + zones;
    }

    private String zoneKindName(ZoneKind kind) {
        return switch (kind) {
            case MAIN_MONSTER_ZONE -> "主怪兽区";
            case EXTRA_MONSTER_ZONE -> "额外怪兽区";
            case SPELL_TRAP_ZONE -> "魔陷区";
            case PENDULUM_ZONE -> "灵摆区";
            case FIELD_ZONE -> "场地魔法区";
            case HAND -> "手牌";
            case GRAVEYARD -> "墓地";
            case BANISHED -> "除外区";
            case DECK -> "卡组";
            case EXTRA_DECK -> "额外卡组";
        };
    }

    /** [表示形式]：攻/守同样是"位置"决策，直接影响战破与战伤。 */
    private String positionSection() {
        return """
                [表示形式决策] 攻/守也是位置决策：
                  - 攻击表示：能打人、能当连接素材，但会被战破、并按攻击力吃战伤；
                  - 守备表示：放弃输出换生存（里侧还能隐藏身份，但对手可据此判断你在守）。
                  判断依据：这回合它需要攻击吗？下回合它需要活着吗？rationale 写一句理由即可。
                """;
    }

    /**
     * 当前计划（直答 的决策主线）：目标 + 思路 + 带进度标记的步骤 + 分支。
     * <p>只给结论不给"为什么"时，模型遇到与计划冲突的候选会随手改计划；
     * 所以思路与 why 也一并给，但都做长度截断，避免把 ④ 的延迟吃掉。
     */
    private String planSection(DecisionPoint point, TurnPlan plan) {
        StringBuilder sb = new StringBuilder();
        boolean stale = plan.turnNumber() != point.turn();
        int cursor = Math.max(0, Math.min(plan.cursor(), plan.steps().size()));
        boolean skeleton = plan.steps().isEmpty() && plan.branches().isEmpty();
        sb.append("[本回合计划] ").append(plan.planId());
        if (stale) {
            sb.append("（上回合制定，仅供参考）");
        }
        if (skeleton && advisoryPlan) {
            // 顾问模式：规划器本来就不给路径，只给分析/战术/combo；决策器负责选动作
            sb.append("（顾问模式：不提供固定路径，以下为战术建议——按它的方向自行选候选）");
        } else if (skeleton) {
            // 路径接不上地已被作废，但战术思想仍然有效：只给目标与思路，别让决策脱离主线
            sb.append("（步骤已作废，以下仅为本回合战术思想——按它判断，不要另起思路）");
        } else {
            sb.append(" 进度 ").append(cursor).append("/").append(plan.steps().size())
                    .append("（▶ = 当前应执行的那一步）");
        }
        sb.append("\n");
        if (!Texts.isBlank(plan.goal())) {
            sb.append("目标：").append(Texts.flattenClip(plan.goal(), 120)).append("\n");
        }
        if (!Texts.isBlank(plan.thinking())) {
            // 顾问模式的 thinking 是"场面/战术/combo"三段拼起来的，截 160 字会把后两段切掉
            // 顾问模式的 thinking = 场面 + 方案比较 + 战术 + combo，截断太短会把"为什么选它"切掉
            sb.append(advisoryPlan ? "建议：" : "思路：")
                    .append(Texts.flattenClip(plan.thinking(), advisoryPlan ? 1_200 : 160)).append("\n");
        }
        if (!plan.steps().isEmpty()) {
            for (int i = 0; i < plan.steps().size(); i++) {
                sb.append("  ").append(i == cursor ? "▶" : (i < cursor ? "✓" : "·"))
                        .append(' ').append(i + 1).append(". ")
                        .append(stepText(plan.steps().get(i))).append("\n");
            }
        }
        if (!plan.branches().isEmpty()) {
            sb.append("分支：\n");
            for (TurnPlan.TurnBranch branch : plan.branches()) {
                String actions = branch.steps().stream().map(this::stepText)
                        .reduce((a, b) -> a + " → " + b).orElse("");
                sb.append("  - ").append(Texts.flattenClip(branch.name(), 40));
                if (!branch.assumes().isEmpty()) {
                    String assumes = branch.assumes().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("、"));
                    sb.append("（").append(Texts.flattenClip(assumes, 80)).append("）");
                }
                sb.append(" → ").append(Texts.flattenClip(actions, 120)).append("\n");
            }
        }
        if (skeleton) {
            sb.append(advisoryPlan
                            ? "执行方式：这里没有动作顺序，具体选哪个候选由你按当前局面决定；"
                            : "执行方式：上面的步骤已失效，请基于这份战术思想结合当前局面自行选择候选；")
                    .append("不要做与建议相反的动作（例如建议是攒资源，就不要为了打点把组件送掉）。\n");
            return sb.toString();
        }
        if (cursor < plan.steps().size()) {
            RequestKind expected = plan.steps().get(cursor).expectedRequest();
            if (point.kind() == RequestKind.POSITION) {
                sb.append("注意：本决策点是**上一步动作的表示形式**，按 [表示形式决策] 判断即可，不要改计划。\n");
                return sb.toString();
            }
            if (expected != point.kind()) {
                // ▶ 步是主阶段的事，但当前是连锁/选卡窗口：计划没覆盖这个时刻，
                // 硬套只会让模型把"主阶段该做什么"错当成"这个窗口该怎么答"。
                sb.append("注意：▶ 那一步在 ").append(expected).append(" 决策点执行，本决策点是 ")
                        .append(point.kind()).append("，计划未覆盖这个窗口 → ")
                        .append("按局面与战术常识自行判断（只要别破坏计划的大方向）。\n");
            }
        }
        sb.append("与计划的关系：若本决策点对应 ▶ 那一步，优先选能兑现它的候选；")
                .append("若该步在当前局面已不合法、或明显不划算（会亏卡/自破场面），可以偏离，")
                .append("但必须在 rationale 里点明\"偏离计划第 N 步，因为……\"。\n");
        return sb.toString();
    }

    private String stepText(PlanStep step) {
        if (step.alternatives().isEmpty()) {
            return "（未给出动作）";
        }
        var action = step.alternatives().getFirst();
        StringBuilder sb = new StringBuilder(action.verb());
        if (action.passcode() > 0) {
            sb.append(' ').append(codeLabel(action.passcode()));
        }
        if (action.zone() != null) {
            // 计划里的位置要跟着显示：否则 直答 看到的计划只有"召唤零衣"，
            // 到了位置决策点时忘了"计划要放 MMZ-3"
            sb.append(" → ").append(ZoneCodes.code(action.zone()));
        }
        Object why = step.expectedEffects().get("why");
        if (why != null && !Texts.isBlank(String.valueOf(why))) {
            sb.append(" — ").append(Texts.flattenClip(String.valueOf(why), 60));
        }
        return sb.toString();
    }

    /** 计划里的卡号 → 可读卡名；卡库没命中时保留卡号，绝不猜。 */
    private String codeLabel(long passcode) {
        return facts.byPasscode(passcode).map(CardFact::label)
                .orElseGet(() -> "卡(" + passcode + ")");
    }
}
