package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorAnswer;
import com.github.coldgust.ygoagent.pipeline.advisor.AdvisorKeeper;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.knowledge.CardFact;

/**
 * ContextPack 组装器（docs/planner-judge-memory-redesign.md）：确定性代码把局面、卡面、数学算好，
 * 组装为幕僚快照与决策快照的上下文。LLM 缺什么就供给什么，供给的都是数据。
 * 静态资料与决斗记录已由 DuelChatMemory/DuelRecordFeed 承载，本类只渲染一次性快照。
 *
 * <p>渲染分三块协作：资料段（{@link AnchorSections}）、局面段（{@link BoardSections}）、
 * 候选段（{@link CandidateSections}）；本类负责装配与决策快照特有的段落
 * （规划器情报 / 当前效果 / 表示形式）。
 */
public final class ContextPackBuilder {

    private final CardFacts facts;
    /** 卡面文本的唯一出口：名字/条款/本次发动的是哪一条，全走它。 */
    private final CardText cardText;
    private final String deckStrategy;
    private final String ruleDigest;
    private final String selfArchetype;
    private final String comboReference;
    /** 己方卡组清单（主+额外，去重）。null/空 = 不注入 [卡组] 段。 */
    private final List<Long> deckPasscodes;

    private final AnchorSections anchor;
    private final BoardSections board;
    private final CandidateSections candidateSections;

    /** 最小构造：可选依赖（思路/规则速查/主题/卡组清单/连招）全部缺席。 */
    public ContextPackBuilder(CardFacts facts) {
        this(facts, null, null, null, null, null);
    }

    /**
     * 可选依赖用 Builder 逐项声明（原来的 3–9 参望远镜重载已删除——
     * 每加一个依赖就加一条重载链的做法在 8 个构造器时已经失控）。
     */
    public static Builder builder(CardFacts facts) {
        return new Builder(facts);
    }

    /** 依赖收集器：字段与 {@link ContextPackBuilder} 的可选参数一一对应。 */
    public static final class Builder {
        private final CardFacts facts;
        private String deckStrategy;
        private String ruleDigest;
        private String selfArchetype;
        private List<Long> deckPasscodes = List.of();
        private String comboReference;

        private Builder(CardFacts facts) {
            this.facts = facts;
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

        public ContextPackBuilder build() {
            return new ContextPackBuilder(facts, deckStrategy, ruleDigest, selfArchetype,
                    deckPasscodes, comboReference);
        }
    }

    private ContextPackBuilder(CardFacts facts, String deckStrategy,
                               String ruleDigest, String selfArchetype,
                               List<Long> deckPasscodes, String comboReference) {
        this.facts = facts;
        this.cardText = new CardText(facts);
        this.deckStrategy = deckStrategy == null || deckStrategy.isBlank() ? null : deckStrategy.trim();
        this.ruleDigest = ruleDigest == null || ruleDigest.isBlank() ? null : ruleDigest.trim();
        this.selfArchetype = selfArchetype == null || selfArchetype.isBlank()
                ? null : selfArchetype.trim();
        this.comboReference = comboReference == null || comboReference.isBlank()
                ? null : comboReference.trim();
        this.deckPasscodes = deckPasscodes == null ? List.of()
                : List.copyOf(deckPasscodes);
        this.anchor = new AnchorSections(facts, this.deckStrategy, this.ruleDigest,
                this.selfArchetype, this.comboReference, this.deckPasscodes);
        this.board = new BoardSections(facts);
        this.candidateSections = new CandidateSections(facts, this.cardText);
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
     * 连招参考与 [卡组] 清单是幕僚专属（回合级战术才用得上），决策器不注入。
     */
    public String judgeStaticMaterial() {
        StringBuilder sb = new StringBuilder();
        anchor.coreSections(sb);
        return sb.toString();
    }

    /**
     * 幕僚静态资料段（整局不变）：核心资料 + [实战连招参考] + [卡组] 全清单。
     * 幕僚负责回合级战术与对手读牌，需要连招库与整副卡组判断"能检索什么、额外能出什么"。
     * 由 DuelAgent 种入 DuelChatMemory 的 [1]——跨回合逐字节一致，前缀缓存整局有效。
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
     * 动态局面段：局面行 / 双方手牌与场面 / 墓地·除外 / 场地槽位。
     * 计数（卡组剩余/场面张数/墓地类型）内联在战场行里；战斗结算由 llm.MathTool 按需提供。
     * 每次决策都可能变，不能进缓存前缀（快照槽内容的主体）。
     */
    public String dynamicBoardPack(DuelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[局面] 第 ").append(state.turnNumber()).append(" 回合，")
                .append(state.isMyTurn() ? "我方回合" : "对方回合")
                .append("，阶段 ").append(state.phase())
                .append("；LP 我 ").append(state.self().lifePoints())
                .append(" / 对 ").append(state.opponent().lifePoints())
                .append("；卡组剩余 我 主").append(state.self().mainDeckCount())
                .append("/额").append(state.self().extraDeckCount())
                .append("，对 主").append(state.opponent().mainDeckCount())
                .append("/额").append(state.opponent().extraDeckCount())
                .append("\n");
        sb.append("[我方手牌] ").append(board.handLine(state, PlayerSide.SELF)).append("\n");
        board.appendHandSpellUsability(sb, state);
        sb.append("[我方场面] ").append(board.fieldLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[我方墓地/除外] ").append(board.graveLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[对手场面] ").append(board.fieldLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手墓地（公开）] ").append(board.graveLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手手牌] ").append(state.opponent().handCount())
                .append(" 张（身份不可见）\n");
        sb.append(board.boardSlotsSection(state));
        return sb.toString();
    }

    /**
     * 幕僚快照（一次性，不入记忆）：局面与相关卡面。决斗记录已在 memory 的追加流里，
     * 不在这里重发（docs §6.3）。
     */
    public String plannerSnapshot(DuelState state) {
        return dynamicBoardPack(state) + anchor.cardFactsSection(state);
    }

    /**
     * 决策快照（docs §7.2，一次性注入、不入记忆）：
     * [局面]/[场地槽位] + [规划器情报] + （从属决策点）[当前效果]/[本次请求] + [候选]/[候选卡面]。
     * 静态资料与决斗记录已在决策器 memory（pinned + 追加流），不在这里重发；
     * 段序与 JudgePrompts.SYSTEM 的快照叙述一致。
     *
     * @param advice      规划器最新建议（AdvisorKeeper 快照；null = 无建议）
     * @param effectScope 正在结算的效果（TurnMemory 解出；空 = 非从属决策点）
     */
    public String judgeSnapshot(DecisionPoint point, AdvisorKeeper.Snapshot advice, String effectScope) {
        StringBuilder sb = new StringBuilder();
        sb.append("[决策快照]\n");
        sb.append(dynamicBoardPack(point.state())).append("\n");
        if (advice != null) {
            String section = adviceSection(point, advice);
            if (!section.isEmpty()) {
                sb.append(section).append("\n");
            }
        }
        if (point.kind() == RequestKind.POSITION) {
            sb.append(positionSection());
        }
        String scope = effectScopeSection(point, effectScope);
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

    /**
     * 一行版候选清单（见 {@link CandidateSections#compactCandidates}）：由编排层拼进决策请求行，
     * 让"这一手能选什么"跟着请求一起进记忆，而不是只存在于用完即弃的快照里。
     */
    public String compactCandidates(DecisionPoint point) {
        return candidateSections.compactCandidates(point);
    }

    /** 请求行抬头：第 N 回合 / 谁的回合 / 阶段 / 这一手是什么（见 {@link CandidateSections#requestHeadline}）。 */
    public String requestHeadline(DecisionPoint point) {
        return candidateSections.requestHeadline(point);
    }

    /** 请求行里的连锁行（空链返回空串）：谁在第几环发动了什么、什么效果、对象是谁。 */
    public String compactChain(DecisionPoint point) {
        return candidateSections.compactChain(point);
    }

    /** 请求行里的候选效果正文（按卡去重）：只有卡名看不出"发动了会怎样"。 */
    public String compactEffectDigest(DecisionPoint point) {
        return candidateSections.compactEffectDigest(point);
    }

    /**
     * {@code [规划器情报]}：幕僚最新建议的决策器视图（docs §6.2）。
     * 只给决策器要用得上的部分（strategy/goal/tactics/intel/combo 选段），
     * 全部做长度截断，避免吃掉 ④ 的延迟预算；keep 跨回合时标"仅供参考"。
     */
    private String adviceSection(DecisionPoint point, AdvisorKeeper.Snapshot advice) {
        AdvisorAnswer a = advice.answer();
        StringBuilder sb = new StringBuilder("[规划器情报] ").append(advice.adviceId());
        if (advice.turn() != point.turn()) {
            sb.append("（上回合制定，仅供参考）");
        }
        sb.append("\n");
        if (!Texts.isBlank(a.strategy())) {
            sb.append("本局策略：").append(Texts.flattenClip(a.strategy(), 200)).append("\n");
        }
        if (!Texts.isBlank(a.goal())) {
            sb.append("本回合目标：").append(Texts.flattenClip(a.goal(), 120)).append("\n");
        }
        if (!Texts.isBlank(a.tactics())) {
            sb.append("战术：").append(Texts.flattenClip(a.tactics(), 200)).append("\n");
        }
        if (!Texts.isBlank(a.intel())) {
            sb.append("情报要点：").append(Texts.flattenClip(a.intel(), 200)).append("\n");
        }
        if (!Texts.isBlank(a.combo())) {
            sb.append("动作链参考：").append(Texts.flattenClip(a.combo(), 400)).append("\n");
        }
        sb.append("与建议的关系：按它判断，不要反向；与 [局面] 冲突以局面为准，")
                .append("偏离需在 rationale 里写明\"偏离建议，因为…\"。\n");
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
}
