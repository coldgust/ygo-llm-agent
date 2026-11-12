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

    private final AnchorSections anchor;
    private final BoardSections board;
    private final CandidateSections candidateSections;

    public ContextPackBuilder(CardFacts facts, DuelMathService math) {
        this(facts, math, null, null);
    }

    /**
     * @param deckStrategy 用户为本卡组写的打法思路（可为空）。它是**人给的**策略知识，
     *                     优先级高于通用战术常识，但要低于当前局面的硬事实。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy) {
        this(facts, math, deckStrategy, null);
    }

    /**
     * @param ruleDigest 游戏王通用规则浓缩版（可为空）。预置在上下文里，模型不必为了
     *                   "召唤条件/连锁速度/战斗结算"这类常识反复检索；精确条文仍可走
     *                   {@code search_rulebook} 工具查规则书原文。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest) {
        this(facts, math, deckStrategy, ruleDigest, null);
    }

    /**
     * @param selfArchetype 己方卡组主题（前端指定，可为空）。作为已知事实注入，
     *                      避免模型去猜"自己是什么系列"。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest, String selfArchetype) {
        this(facts, math, deckStrategy, ruleDigest, selfArchetype, false);
    }

    /**
     * @param advisoryPlan 顾问模式：规划器不产出可执行路径，计划段落按"战术建议"渲染。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest, String selfArchetype, boolean advisoryPlan) {
        this(facts, math, deckStrategy, ruleDigest, selfArchetype, advisoryPlan, null);
    }

    /**
     * @param deckPasscodes 己方卡组清单（主+额外）。给全是为了让规划器知道"能检索到什么、能特召什么"，
     *                      而不是每次靠 {@code find_card} 现查——实战里查一轮就是好几秒。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest, String selfArchetype, boolean advisoryPlan,
                              List<Long> deckPasscodes) {
        this(facts, math, deckStrategy, ruleDigest, selfArchetype, advisoryPlan, deckPasscodes, null);
    }

    /**
     * @param comboReference 实战连招参考文本（PG combo 库按主题取最近几条，可为空）。
     *                       它回答"这套卡组实际怎么打"：真实玩家的展开顺序、终端与代价，
     *                       比通用战术常识更贴近本卡组；同样只是资料，局面硬事实优先。
     */
    public ContextPackBuilder(CardFacts facts, DuelMathService math, String deckStrategy,
                              String ruleDigest, String selfArchetype, boolean advisoryPlan,
                              List<Long> deckPasscodes, String comboReference) {
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
        this.anchor = new AnchorSections(facts, this.deckStrategy, this.ruleDigest,
                this.selfArchetype, this.comboReference, this.deckPasscodes);
        this.board = new BoardSections(facts);
        this.candidateSections = new CandidateSections(facts, this.cardText);
    }

    /**
     * 游戏王通用规则速查（浓缩版）。
     *
     * <p>规则是长期稳定的知识：直接内嵌在代码里即可，不必每次启动从文件或 RAG 加载——
     * 零 IO、离线可用，也不会因为部署漏拷资源而静默丢失。
     * 需要精确条文/冷门判定时，仍由模型调用 {@code search_rulebook} 查规则书原文。
     */
    public static final String RULE_DIGEST = """
            一、场地与基本数字
            - 主卡组 40–60；额外卡组 ≤15（融合/同调/超量/连接）；副卡组 ≤15。
            - 场地：主怪兽区 5、魔陷区 5、场地魔法区 1、额外怪兽区 2（双方各 2，只能放从额外卡组特召的连接/灵摆怪兽）、灵摆区 2。
            - 起始 LP 8000、手牌 5。先手第 1 回合不抽卡、不能攻击。EP 手牌超过 6 张弃到 6。

            二、回合与优先权
            抽卡(DP) → 准备(SP) → 主要阶段1(M1) → 战斗(BP) → 主要阶段2(M2) → 结束(EP)。
            - 每个时点由回合玩家先拿到优先权，双方都放弃才进入下一阶段或结算。
            - 能发动的只有"这个时点合法"的效果；发动后对方可连锁，连锁**逆算**（最后发动的先结算）。
            - 伤害步骤内只能发动：反击陷阱、明确写在"伤害步骤/伤害计算时"的效果、改变攻守的效果、
              以及无效对方发动的效果；一般的起动效果与诱发即时效果在伤害步骤内不能发动。

            三、召唤
            - 通常召唤每回合 1 次（表侧攻击表示，或里侧守备盖放）。5–6 星 1 只祭品，7 星以上 2 只。
            - 特殊召唤不占通召次数（卡面另说除外）：融合/同调/超量/连接/仪式/灵摆。
              同调：调整+非调整，等级合计相等；超量：同等级叠放，按阶级用；连接：素材数 = LINK 值。
            - 反转召唤：里侧守备 → 表侧攻击，不占通召次数。
            - 连接怪兽没有守备表示，不能盖放、不能转守备。
            - 灵摆怪兽从**场上**送去墓地时不进墓地，而是表侧加入额外卡组（从手牌/卡组送墓仍进墓地）。

            四、战斗
            - 攻对攻：比 ATK，低者破坏，差值扣 LP。攻对守：ATK>DEF 破坏且不扣 LP（贯穿除外）；
              ATK<DEF 时攻击方吃差值；相等都不破坏。
            - 守备表示不能攻击；对方场上没有怪兽才能直接攻击。
            - 战斗破坏和效果破坏都是"破坏"，但"因战斗被破坏"与"被效果破坏"触发的效果不是一回事。

            五、咒文速度与连锁结算（含"发动 ≠ 生效"）
            - 速度 1：通常/装备/场地/永续/仪式魔法、怪兽的起动效果与诱发效果。
            - 速度 2：速攻魔法、通常陷阱、永续陷阱、诱发即时效果。
            - 速度 3：反击陷阱。
            - 只能被同速或更高速度连锁；连锁逆算，后发先结算。
            - 一段效果要走完三步才算数：**发动 → 结算 → 生效**。连锁上只是"已发动"，
              结算前一律视为"还不知道结果"。
            - 逆算推演：从**最后一环往前**逐环问"轮到我这一环时，它还在吗、还有效吗"。
              链上后发的无效/除外/无效并除外类效果**先结算**，可以让前面任何一环作废——包括我方发的。
            - 所以只要连锁上还有"针对我方那一环"的无效类效果，就不能说自己那一环"已无效对方"、
              "已经通过了"；正确说法是"取决于它结算时是否被无效"。
            - 反过来，**场面是已发生结果的记录**：如果场上/墓地/除外区出现了"只有该效果通过才会有的东西"
              （对方场上多出怪兽、我方卡被除外、某张卡进了墓地），那就是它已经结算并生效了。
              此时以 [局面] 为准，撤回之前"它应该被无效了"的预期，不要用预期去解释反证。
            - 判据句：**"我手里/链上的这一环，到它结算时还成立吗？"** 答不上来就不能当既成事实用。

            六、六类魔法卡与发动时机（最常错的一段）
            - 通常魔法：自己主要阶段从手牌发动。**盖放的通常魔法，盖的当回合就能发动**。
            - 速攻魔法：从**手牌**发动只能在自己回合的主要阶段；想在对手回合用，必须提前一回合盖到魔陷区。
            - 永续/装备/场地/仪式魔法：自己主要阶段发动；盖的当回合也能发动，盖放对它们只等于"先藏着"。
            - 陷阱：必须先盖，且**盖的当回合不能发动**（卡面写明可从手牌发动的除外）。
            - 所以"盖的当回合不能发动"这条**只适用于速攻魔法和陷阱**，不要套到其他魔法上。
            - **对手回合**能发动的牌只有三类，别把别的算进去：
              ① 已盖在魔陷区的速攻魔法 / 陷阱（盖的当回合不能发，所以必须**上一回合**盖）；
              ② 手牌里卡面写明"自己·对方回合，把这张卡从手卡丢弃/送去墓地才能发动"的怪兽（手坑）；
              ③ 场上已表侧的效果（诱发即时、永续效果）。
            - 反过来说死：**手牌里的速攻魔法、通常魔法、陷阱，在对手回合一律发不出来**
              （除非卡面明写可以从手牌发动）。留手 ≠ 留着能用。
            - 所以"给对手回合留速攻魔法当阻抗"= **这回合先盖下去**；留在手牌等于放弃它整个对手回合。
              回合结束前该自检一遍：手上还有几张速攻魔法/陷阱？要留阻抗就现在盖。
            - 盖放本身不产生任何效果：它只是把卡放进魔陷区。别把"盖了一张"当成"多了一个阻抗"。

            七、无效 / 破坏 / 除外 / 送墓：四种结果互不等价
            - 效果无效：效果不发生，但卡**不破坏、仍留在场**；ATK/DEF 不变（卡面另说除外）。
              所以"无效"既不能用来压打点，也不能解掉一只打不过的怪兽。
            - 被无效的效果**仍算已经发动过**（"效果发动时"类触发照样成立），只是效果不处理。
            - 破坏 → 送墓；但"不会被效果破坏"的卡只是挡破坏，仍可能被除外/送墓/弹回手牌。
            - 除外 ≠ 送墓 ≠ 回手牌/回卡组，各自触发不同的效果；"不能破坏"不等于"不能被除去"。
            - 召唤/特殊召唤被无效时，那只怪兽视为从未上场，不触发"被破坏""送去墓地"类效果。

            八、取对象 / 不取对象（决定"能不能选到它"）
            - 卡面里的「自己 / 对方」是**发卡者的视角**：对手的卡上写"对方"，指的就是我方
              （我方墓地 / 我方场上 / 我方手牌）。对手写"以对方墓地1只怪兽为对象"，就是从**我方墓地**选，
              而且往往是冲着我方刚丢进墓地的那只怪兽来的——这类效果会把它除外并无效化。
            - 取对象：卡面写"以…为对象"，发动时就选定；结算时对象已不在原处（离场、变里侧等）→ 该效果不处理。
            - 不取对象：卡面写"选/那些/全部/场上所有"，发动时不选，结算时按当时场面处理。
            - 「不能作为效果的对象」只挡取对象的效果，不取对象的效果照样能作用它。
            - 「不受效果影响」才是全挡（取对象与不取对象都挡）。

            九、什么时候"无效 / 除去"是白亏（通用判据，任何卡都用）
            1) 这个效果**接下来还会不会再结算一次**？不会（召唤时诱发已经处理完、效果已经用完）
               → 现在去无效它是净亏一张。
            2) 这个效果是从**哪个场所**发动的？若是墓地/手牌/卡组/除外的效果，
               无效"场上的这张卡"对它没有影响。
            3) 无效之后我实际多了什么（保住怪兽？拦住检索？让攻击通过？）说不出来 → 不要发。
            4) 对手没有参与这一环（只是自己在处理自己的效果）时，追加发卡几乎都是白亏。

            十、其他高频误区
            - 「1回合1次」按卡名计（同名卡合计）；「这个卡名的①效果1回合1次」按卡名+效果编号计。
            - 超量素材不是卡：超量怪兽被破坏时素材送墓，但不触发"卡被送去墓地"类效果。
            - 衍生物离场即消灭，不进墓地，不触发"送去墓地"的效果。
            - 里侧守备怪兽被攻击时翻开（反转），反转效果在此时发动；里侧怪兽的信息对手不可见。
            - 墓地/除外是公开信息；手牌/卡组/额外卡组是隐藏信息。
            - 效果文本里的「才能发动」是发动条件，「的场合」是触发条件（可以不发）；
              但**条件是否满足由引擎判定**：候选集与"是否发动"请求递到你面前，就说明它此刻发得出来。
            """;

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
        anchor.strategySections(sb);
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
     * 规划器专用锚点：{@link #anchorPack} + {@code [卡面]} 段。
     */
    public String plannerAnchorPack(DuelState state) {
        return anchorPack(state) + anchor.cardFactsSection(state) + anchor.deckSection();
    }

    public String decisionPack(DecisionPoint point) {
        return decisionPack(point, null);
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
        sb.append(anchorPack(point.state())).append("\n");
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
        String history = historySection(point, remembered);
        if (!history.isEmpty()) {
            sb.append(history).append("\n");
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
     * {@code [本回合已做]}：把"你已经做过什么、上一手怎么想的"补进上下文。
     */
    private String historySection(DecisionPoint point, DecisionMemory memory) {
        if (memory == null || memory.turnActions().isEmpty() && memory.lastIntent().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!memory.turnActions().isEmpty()) {
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
        sb.append("  要推翻上面的决定可以，但 rationale 里必须说清「为什么改」；")
                .append("不要不声不响地做出相反的选择。\n");
        sb.append("  「上一手的判断」只是你当时的判断，不是事实：若 [局面] 显示的结果与它相反")
                .append("（对方场上多出了怪兽、我方卡被除外/破坏、某张卡进了墓地），一律以 [局面] 为准，")
                .append("并在 rationale 里写明「上一手的判断被结果推翻」。\n");
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
