package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.plan.PlanStep;
import com.github.coldgust.ygoagent.decision.plan.ZoneCodes;
import com.github.coldgust.ygoagent.decision.planexec.planner.TurnPlan;
import com.github.coldgust.ygoagent.decision.planexec.prompt.CardText;
import com.github.coldgust.ygoagent.decision.planexec.prompt.DecisionMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ContextPack 组装器（v6 §3.2/§3.4）：确定性代码把局面、卡面、数学算好，
 * 组装为规划器锚点与执行器 one-shot 的上下文。LLM 缺什么就供给什么，供给的都是数据。
 */
public final class ContextPackBuilder {

    /** 卡组思路上限：太长会挤占局面/候选的注意力，超出部分截断（前段通常是最重要的）。 */
    private static final int DECK_STRATEGY_LIMIT = 2_500;

    /**
     * 规则速查上限：它是通用背景知识，不能挤掉局面与候选，超长同样截断。
     * <p>实测证明"够用即止"的摘要不够用——模型会自己补规则，补出来的往往是错的
     * （把"手牌的速攻魔法"当成"对手回合能用"、把"无效效果"当成"压打点"）。
     * 所以宁可多花这点上下文，也要把高频误区和判定判据写全；前段是最高频的错误源。
     */
    private static final int RULE_DIGEST_LIMIT = 8_000;

    private final CardFacts facts;
    /** 卡面文本的唯一出口：名字/条款/本次发动的是哪一条，全走它。 */
    private final CardText cardText;
    private final DuelMathService math;
    private final String deckStrategy;
    private final String ruleDigest;
    private final String selfArchetype;
    /** 实战连招参考（PG combo 库按主题取最近 N 条的渲染文本，可为空）。 */
    private final String comboReference;
    /** 顾问模式：计划只有战术建议、没有可执行步骤（{@code PLANNER_PATH_PLANNING=false}）。 */
    private final boolean advisoryPlan;
    /** 己方卡组清单（主+额外，去重）。null/空 = 不注入 [卡组] 段。 */
    private final List<Long> deckPasscodes;

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

    /** 回合锚点（规划器 session 的锚点消息）：局面 + 卡面 + 数学，无候选。 */
    public String anchorPack(DuelState state) {
        StringBuilder sb = new StringBuilder();
        appendSelfArchetype(sb);
        appendDeckStrategy(sb);
        appendComboReference(sb);
        appendRuleDigest(sb);
        sb.append("[局面] 第 ").append(state.turnNumber()).append(" 回合，")
                .append(state.isMyTurn() ? "我方回合" : "对方回合")
                .append("，阶段 ").append(state.phase())
                .append("；LP 我 ").append(state.self().lifePoints())
                .append(" / 对 ").append(state.opponent().lifePoints()).append("\n");
        sb.append("[我方手牌] ").append(handLine(state, PlayerSide.SELF)).append("\n");
        appendHandSpellUsability(sb, state);
        sb.append("[我方场面] ").append(fieldLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[我方墓地/除外] ").append(graveLine(state, PlayerSide.SELF)).append("\n");
        sb.append("[对手场面] ").append(fieldLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手墓地（公开）] ").append(graveLine(state, PlayerSide.OPPONENT)).append("\n");
        sb.append("[对手手牌] ").append(state.opponent().handCount())
                .append(" 张（身份不可见）\n");
        sb.append(boardSlotsSection(state));
        // 只注入确定性计数；斩杀判定已停用（回合开始时必然"不可斩杀"，无信息量且误导）
        sb.append(math.constraintsSection(state, facts));
        return sb.toString();
    }

    /**
     * 规划器专用锚点：{@link #anchorPack} + {@code [卡面]} 段。
     *
     * <p>为什么单独给规划器：判断"卡与卡怎么配合"必须看到效果原文。只给卡名，
     * 模型只能凭训练记忆猜——实测就退化成"这张卡能检索""那张卡能特召"的逐张单卡评价，
     * 而说不出"零衣解放→燎里回收墓地魔法→交闪再检索"这类闭环。
     *
     * <p>为什么不加进 {@link #anchorPack}：决策包每个决策点都拼一次锚点，
     * 一局 60 多次调用，多一两千字是实打实的成本；而规划器每回合只发一两次锚点。
     */
    public String plannerAnchorPack(DuelState state) {
        return anchorPack(state) + cardFactsSection(state) + deckSection();
    }

    /** [卡组] 段预算。 */
    private static final int DECK_SECTION_LIMIT = 7_000;
    private static final int DECK_EFFECT_LIMIT_EXTRA = 160;
    private static final int DECK_EFFECT_LIMIT_MAIN = 110;

    /**
     * {@code [卡组]}：己方整副卡组（主+额外）的卡名/种类/攻守/效果摘要。
     *
     * <p>为什么要给全：规划器要判断"这张检索能拿到什么""额外卡组有没有能出的东西"，
     * 只靠 {@code find_card} 现查，一轮就是好几秒，而卡组是自己的、本来就是已知信息。
     * 效果做长度分层：额外卡组（真会出场的）给得全，主卡组只留摘要，控制体积。
     */
    private String deckSection() {
        if (deckPasscodes.isEmpty()) {
            return "";
        }
        LinkedHashSet<Long> deckOrder = new LinkedHashSet<>(deckPasscodes);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int listed = 0;
        for (Long passcode : deckOrder) {
            if (passcode == null || passcode <= 0) {
                continue;
            }
            CardFact fact =
                    facts.byPasscode(passcode).orElse(null);
            if (fact == null) {
                continue;
            }
            boolean extra = fact.isMonster() && fact.level() == null && fact.defense() == null;
            String effect = fact.effect() == null ? "" : fact.effect().replaceAll("\\s+", " ").trim();
            StringBuilder line = new StringBuilder("- ").append(fact.label());
            StringBuilder meta = new StringBuilder();
            if (!isBlank(fact.cardType())) {
                meta.append(fact.cardType());
            }
            if (fact.atk() != null) {
                meta.append(meta.isEmpty() ? "" : "｜").append("ATK").append(fact.atk())
                        .append(fact.defense() == null ? "" : "/DEF" + fact.defense());
            }
            if (!meta.isEmpty()) {
                line.append("〔").append(meta).append("〕");
            }
            if (!effect.isEmpty()) {
                line.append("：").append(clip(effect,
                        extra ? DECK_EFFECT_LIMIT_EXTRA : DECK_EFFECT_LIMIT_MAIN));
            }
            line.append('\n');
            if (used + line.length() > DECK_SECTION_LIMIT) {
                break;
            }
            if (listed == 0) {
                sb.append("[卡组] 己方卡组（这是你自己的卡组清单，可用它判断能检索/特召什么；")
                        .append("需要更完整的效果再调 find_card）：\n");
            }
            sb.append(line);
            used += line.length();
            listed++;
        }
        if (listed == 0) {
            return "";
        }
        sb.append("（共列出 ").append(listed).append(" 张；同名卡只列一次）\n");
        return sb.toString();
    }

    /** 单卡效果原文的预算：卡片数上限、每张截断长度、整段上限。 */
    private static final int CARD_FACT_MAX_CARDS = 14;
    private static final int CARD_FACT_EFFECT_LIMIT = 180;
    private static final int CARD_FACT_TOTAL_LIMIT = 2_200;

    /**
     * {@code [卡面]}：我方手牌/场上已知卡的效果原文。
     *
     * <p>措辞里点明"未列出 = 卡库没有资料"，避免模型把"没写"当成"没有效果"去编。
     */
    private String cardFactsSection(DuelState state) {
        LinkedHashMap<Long, CardFact> cards =
                new LinkedHashMap<>();
        List<CardInstance> owned = new ArrayList<>();
        owned.addAll(state.player(PlayerSide.SELF).hand());
        owned.addAll(state.monsters(PlayerSide.SELF));
        owned.addAll(state.spellTraps(PlayerSide.SELF));
        // 对手的**明牌**也一起给：场上表侧的怪兽/魔陷是公开信息，本来就要拿来判断"会不会被无效/除外"。
        // 不预置的话模型会为每一张明牌各调一次工具，实测一个回合光查卡就 10s+。
        owned.addAll(state.monsters(PlayerSide.OPPONENT));
        owned.addAll(state.spellTraps(PlayerSide.OPPONENT));
        for (CardInstance card : owned) {
            if (card.isUnknown() || card.passcode() <= 0) {
                continue;
            }
            facts.byPasscode(card.passcode()).ifPresent(fact -> {
                if (fact.effect() != null && !fact.effect().isBlank()) {
                    cards.putIfAbsent(fact.passcode(), fact);
                }
            });
        }
        if (cards.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "[卡面] 双方已知卡的效果（我方手牌/场上 + 对手明牌；未列出的＝卡库无资料，不要凭印象编效果）\n");
        int used = 0;
        int count = 0;
        for (CardFact fact : cards.values()) {
            if (count >= CARD_FACT_MAX_CARDS || used >= CARD_FACT_TOTAL_LIMIT) {
                break;
            }
            String effect = fact.effect().replaceAll("\\s+", " ").trim();
            String line = "- " + fact.label()
                    + (isBlank(fact.cardType()) ? "" : "〔" + fact.cardType() + "〕")
                    + "：" + clip(effect, CARD_FACT_EFFECT_LIMIT) + "\n";
            sb.append(line);
            used += line.length();
            count++;
        }
        return sb.toString();
    }

    /**
     * [卡组思路]：用户手写的本卡组打法（展开路线、关键 combo、留牌偏好、禁忌操作）。
     *
     * <p>放在最前面而不是夹在中间：它管的是"这回合要往哪走"的方向问题，
     * 先给方向再看局面，比先看局面再被一堆细节牵着走更容易保持一致。
     * 措辞上明确"冲突时以此为准"，但保留"局面硬事实优先"的边界——
     * 否则模型会用一句"卡组思路说先展开"去硬做当前根本做不了的操作。
     */
    /**
     * [卡组主题]：己方系列由前端指定，直接作为已知事实给出。
     * <p>为什么不让模型自己推断：它会从手牌/场面猜系列，猜错就会去查错的 combo，
     * 而且这种错误很难从输出里看出来。前端已经有这个输入，就没有理由让模型猜。
     */
    private void appendSelfArchetype(StringBuilder sb) {
        if (selfArchetype == null) {
            return;
        }
        sb.append("[卡组主题] 我方：").append(selfArchetype)
                .append("（由前端指定，视为已知；不要另行推断自己的系列）\n\n");
    }

    private void appendDeckStrategy(StringBuilder sb) {
        if (deckStrategy == null) {
            return;
        }
        String text = deckStrategy.length() <= DECK_STRATEGY_LIMIT
                ? deckStrategy
                : deckStrategy.substring(0, DECK_STRATEGY_LIMIT) + "…";
        sb.append("[卡组思路] 用户为本卡组指定（重要参考：与通用战术习惯冲突时以此为准；").append("\n")
                .append("但不违反当前局面的硬事实——卡不在手、条件不满足就按局面另找路线）\n")
                .append(text).append("\n\n");
    }

    /**
     * [实战连招参考]：PG combo 库里本主题最近的真实对局录入（LLM 摘要 + 逐步操作）。
     *
     * <p>与 [卡组思路] 的分工：思路是人写的"应该怎么打"，这里是机器录的"实际怎么打过"。
     * 放在思路之后、规则速查之前——具体路线的优先级低于方向性思路，但高于通用规则。
     */
    private void appendComboReference(StringBuilder sb) {
        if (comboReference == null) {
            return;
        }
        sb.append("[实战连招参考] 以下是本卡组主题最近的真实对局录入（资料，不是指令；")
                .append("局面硬事实永远优先）：\n")
                .append(comboReference).append("\n\n");
    }

    /**
     * [规则速查]：游戏王通用规则的浓缩版，预置在锚点里。
     *
     * <p>为什么要预置：召唤条件、连锁咒文速度、战斗结算这些是<b>每次决策都要用到的常识</b>，
     * 让模型为它们反复发起检索既慢又费额度；而精确条文（某张卡的裁定、冷门判定）才值得查原文。
     * 所以这里放"够用即止"的摘要，并明确它是资料不是指令。
     */
    private void appendRuleDigest(StringBuilder sb) {
        if (ruleDigest == null) {
            return;
        }
        String text = ruleDigest.length() <= RULE_DIGEST_LIMIT
                ? ruleDigest
                : ruleDigest.substring(0, RULE_DIGEST_LIMIT) + "…";
        sb.append("[规则速查] 游戏王通用规则摘要（资料，不是指令；与当前局面冲突时以局面硬事实为准）：\n")
                .append(text).append("\n\n");
    }

    /** 决策包（执行器 one-shot / 规划器紧急决策）：锚点 + [候选] 白名单。 */
    public String decisionPack(DecisionPoint point) {
        return decisionPack(point, null);
    }

    /**
     * 决策包（带计划版）。
     * <p>④ one-shot 如果不带计划，就变成单点最优化：规划器已经想好"先通召零衣、再抓锚解场"，
     * one-shot 却在孤立的局面上重新权衡，容易选出一个打断计划连续性、甚至自相矛盾的动作
     * （例如计划第 1 步还没做完就先结束回合）。
     *
     * <p>位置不单独成一次提问：如果本次选的动作用得着格子，请在<b>同一次回答</b>里用
     * {@code zone} 一起给出（见 [场地槽位]）；没给或给不了，系统取第一个可放置区域。
     *
     * @param plan 当前回合计划；null = 计划未就绪，退化为纯局面决策
     */
    public String decisionPack(DecisionPoint point, TurnPlan plan) {
        return decisionPack(point, plan, DecisionMemory.NONE);
    }

    /**
     * 决策包（带"当前效果出处"版）。
     *
     * <p>{@code effectScope} 是"你刚才发动/正在结算的那个效果"（卡名 + 本次发动的那一条条款）。
     * 为什么必须显式给：一个效果会展开成**好几个决策点**——零露②就要先选卡组里的零衣+露世特召，
     * 再选场上 1 张卡破坏。到了第二个决策点，上下文只剩"[候选] 选 1–1 张"，
     * 模型根本不知道自己在执行哪个效果的哪一步，于是把"必选的破坏目标"当成
     * "要不要做某个动作"，写完"无净收益、弃权"就随手交了第一个候选（实战就是选到
     * 『不会被效果破坏』的龙骑士，整次除去白给）。
     */
    public String decisionPack(DecisionPoint point, TurnPlan plan, String effectScope) {
        return decisionPack(point, plan, DecisionMemory.of(effectScope, List.of(), ""));
    }

    /**
     * 决策包（带"记忆"版）：本回合已做的动作 + 上一手的判断 + 正在结算的效果。
     *
     * <p>one-shot 每手都是独立单轮，没有对话历史，所以"我刚做过什么"必须由代码显式补上；
     * 否则模型会频繁推翻自己——判了"保留零露作终盘阻抗"，几十秒后又把它解放掉。
     */
    public String decisionPack(DecisionPoint point, TurnPlan plan, DecisionMemory memory) {
        DecisionMemory remembered = memory == null ? DecisionMemory.NONE : memory;
        StringBuilder sb = new StringBuilder();
        sb.append(anchorPack(point.state())).append("\n");
        if (plan != null && (!plan.steps().isEmpty() || !plan.branches().isEmpty()
                || !isBlank(plan.goal()) || !isBlank(plan.thinking()))) {
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
        sb.append(candidatesSection(point));
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

    /** 本回合动作记录的条数上限（再多也只是噪声）。 */
    private static final int HISTORY_MAX_ACTIONS = 8;

    /**
     * {@code [本回合已做]}：把"你已经做过什么、上一手怎么想的"补进上下文。
     *
     * <p>one-shot 不带对话历史，模型看不到自己前面的决定，这是"前后矛盾"的根源：
     * 上一手判"泡影留给对方上级怪兽"，下一手在检索环节就交了；上一手判"零露是终盘阻抗"，
     * 后面又把它解放掉。这里只列<b>既成事实</b>，并把这条约束说清：
     * <b>可以改主意，但要在 rationale 里说明为什么改</b>。
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
        if (!isBlank(effectScope)) {
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
     * 当前计划（one-shot 的决策主线）：目标 + 思路 + 带进度标记的步骤 + 分支。
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
        if (!isBlank(plan.goal())) {
            sb.append("目标：").append(clip(plan.goal(), 120)).append("\n");
        }
        if (!isBlank(plan.thinking())) {
            // 顾问模式的 thinking 是"场面/战术/combo"三段拼起来的，截 160 字会把后两段切掉
            // 顾问模式的 thinking = 场面 + 方案比较 + 战术 + combo，截断太短会把"为什么选它"切掉
            sb.append(advisoryPlan ? "建议：" : "思路：")
                    .append(clip(plan.thinking(), advisoryPlan ? 1_200 : 160)).append("\n");
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
                sb.append("  - ").append(clip(branch.name(), 40));
                if (!branch.assumes().isEmpty()) {
                    String assumes = branch.assumes().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("、"));
                    sb.append("（").append(clip(assumes, 80)).append("）");
                }
                sb.append(" → ").append(clip(actions, 120)).append("\n");
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
            // 计划里的位置要跟着显示：否则 one-shot 看到的计划只有"召唤零衣"，
            // 到了位置决策点时忘了"计划要放 MMZ-3"
            sb.append(" → ").append(ZoneCodes.code(action.zone()));
        }
        Object why = step.expectedEffects().get("why");
        if (why != null && !isBlank(String.valueOf(why))) {
            sb.append(" — ").append(clip(String.valueOf(why), 60));
        }
        return sb.toString();
    }

    /** 计划里的卡号 → 可读卡名；卡库没命中时保留卡号，绝不猜。 */
    private String codeLabel(long passcode) {
        return facts.byPasscode(passcode).map(CardFact::label)
                .orElseGet(() -> "卡(" + passcode + ")");
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String clip(String text, int limit) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /** 候选白名单的可读渲染（按请求类型分组）。 */
    public String candidatesSection(DecisionPoint point) {
        StringJoiner sj = new StringJoiner("\n");
        ChoiceRequest request = point.request();
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            appendMainPhaseCards(sj, point, m);
            appendEffectGroup(sj, "发动效果", point.candidatesOf(Candidate.EffectC.class));
            appendFlags(sj, point);
            appendEndTurnAudit(sj, point, m);
        } else if (request instanceof ChoiceRequest.BattlePhaseRequest b) {
            appendEffectGroup(sj, "发动效果", point.candidatesOf(Candidate.EffectC.class));
            appendGroup(sj, "攻击宣言", point.candidatesOf(Candidate.AttackC.class),
                    c -> attackLabel(c.value()));
            appendFlags(sj, point);
        } else if (request instanceof ChoiceRequest.CardSelectRequest s) {
            sj.add("选 [" + s.min() + "," + s.max() + "] 张" + (s.cancelable() ? "（可取消）" : "（必选）"));
            appendGroup(sj, "候选卡", point.candidatesOf(Candidate.CardC.class), c -> cardLabel(c));
        } else if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            sj.add("选祭品 [" + t.min() + "," + t.max() + "] 张");
            appendGroup(sj, "祭品候选", point.candidatesOf(Candidate.TributeC.class),
                    c -> cardLabel(new Candidate.CardC(c.id(), c.value().card())));
        } else if (request instanceof ChoiceRequest.ChainRequest c) {
            appendChainBlock(sj, point, c);
            sj.add(c.hasForcedEffect()
                    ? "有必发效果：必须选中标 [必发] 的项，可再叠加自己的选发效果"
                    : "全部为选发效果：可以响应，也可以弃权保留资源");
            appendChainEffects(sj, point, c);
            appendDeclines(sj, point);
        } else if (request instanceof ChoiceRequest.EffectYesNoRequest y) {
            // 必须说清"要发动的是哪一条效果"。只给卡名 + 效果号时，模型只能凭记忆套——
            // 实机：零衣在墓地、引擎问的是 ②（从墓地自身特召），模型却按 ①（解放自身）判断，
            // 得出"①无法解放自身 → 不发动，保留资源"，把一次免费的资源回收当成了空过。
            String fired = cardText.fired(y.card().identity().passcode(), y.description());
            sj.add("是否发动：" + effectCardLabel(y.card())
                    + (fired.isEmpty() ? "" : " —— 这次要发动的是：" + fired));
            // 这一句是必须的：引擎只有在"这个效果此刻可以发动"时才会发这个请求。
            // 不写的话模型会拿卡面条件去验（实机：零衣在墓地，它写"①②均不满足，无法发动"，
            // 而引擎正是因为条件成立才来问的——一次可发的资源点被当成空过）。
            sj.add("  ↑ 引擎只在「这个效果此刻可以发动」时才会问这一句：这是**要不要发**，不是**能不能发**。"
                    + "不要在理由里写「条件不满足 / 无法发动」——那说明你记错了规则，此时请重读上面的效果原文。");
            appendEffectGroup(sj, "发动", point.candidatesOf(Candidate.EffectC.class));
            appendDeclines(sj, point);
        } else if (request instanceof ChoiceRequest.YesNoRequest y) {
            sj.add("是/否确认（description 码 " + y.description() + "）");
            appendDeclines(sj, point);
        } else if (request instanceof ChoiceRequest.UnselectRequest u) {
            // 这一步几乎总是"上一个动作的后续"（选素材/选送墓/选保留），不是独立决策点。
            // 以前它落到兜底分支，模型只看到"候选 1 个"，就把 C0 当成"空过/结束回合"用，
            // 于是取消→动作回滚→引擎再问一遍→再取消，形成死循环。
            sj.add("上一动作的选卡步骤：需选 [" + u.min() + "," + u.max() + "] 张"
                    + "（已选 " + u.selected().size() + " 张）");
            appendGroup(sj, "可选", point.candidatesOf(Candidate.CardC.class).stream()
                    .filter(c -> !u.selected().contains(c.value())).toList(), c -> cardLabel(c));
            if (!u.selected().isEmpty()) {
                sj.add("已选：" + u.selected().stream()
                        .map(c -> cardLabel(new Candidate.CardC(
                                CandidateId.of(0), c)))
                        .reduce((a, b) -> a + "、" + b).orElse(""));
            }
            if (u.min() > 0) {
                sj.add("必须选满 " + u.min() + " 张：取消会放弃整个上一步动作、引擎随即重问同样的选项，"
                        + "不要取消（本请求不提供取消项）");
            } else {
                appendDeclines(sj, point);
            }
        } else if (request instanceof ChoiceRequest.SumRequest s) {
            sj.add("凑和 " + s.targetSum() + "（必选 " + s.mustSelect().size() + " 张在前）");
            appendGroup(sj, "必选", point.candidatesOf(Candidate.CardC.class).stream()
                            .limit(s.mustSelect().size()).toList(),
                    c -> cardLabel(c));
        } else if (request instanceof ChoiceRequest.PlaceRequest p) {
            sj.add("选 " + p.count() + " 个区域（这是本条动作的放置位置，见 [场地槽位]）");
            appendGroup(sj, "可用区域", point.candidatesOf(Candidate.ZoneC.class),
                    c -> zoneLabel(c));
        } else {
            sj.add(point.kind() + "：候选 " + point.candidates().size() + " 个（详情走 Reflex/one-shot）");
        }
        appendCandidateCardFacts(sj, point);
        return sj.toString();
    }

    /* ---------- 渲染细节 ---------- */

    /**
     * 主阶段的卡类候选按"实际能做什么"分组。
     *
     * <p>以前一律打成"通常召唤"：把只能盖放的魔法、只能特招的额外怪兽都标成"通常召唤"，
     * 模型据此选出来的动作经常不是它以为的那个；而位置决策又恰恰取决于动词
     * （召唤落怪兽区、盖魔陷落魔陷区），标签错了位置也必然跟着错。
     * 分组顺序与 {@code Candidates.from} 的生成顺序一致；万一不一致就退化成统一标签，不猜。
     */
    private void appendMainPhaseCards(StringJoiner sj, DecisionPoint point,
                                      ChoiceRequest.MainPhaseRequest m) {
        List<Candidate.CardC> cards = point.candidatesOf(Candidate.CardC.class);
        int[] sizes = {m.normalSummonable().size(), m.specialSummonable().size(),
                m.repositionable().size(), m.monsterSettable().size(), m.spellTrapSettable().size()};
        String[] titles = {"通常召唤", "特殊召唤/连接召唤", "变更表示形式", "盖放怪兽", "盖放魔陷"};
        int total = 0;
        for (int s : sizes) {
            total += s;
        }
        if (total != cards.size()) {
            appendGroup(sj, "可选动作", cards, this::cardLabel);
            return;
        }
        int from = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] > 0) {
                appendGroup(sj, titles[i], cards.subList(from, from + sizes[i]), this::cardLabel);
            }
            from += sizes[i];
        }
        if (total > 0) {
            sj.add("（本步若会落场，可在同一次回答里加 \"zone\": \"MMZ-3\" 指明放到哪一格；"
                    + "见 [场地槽位]，只能写当前空格。省略则系统取第一个可放置区域）");
        }
    }

    /**
     * 连锁上下文：当前链的每一环（发动者 / 卡名 / 效果号 / 该卡效果摘要）。
     * <p>没有这块，模型只能看到"有 3 个效果可连锁"，却不知道现在到底在响应什么——
     * 而连锁判断的第一依据正是"我现在要打断的是哪一环"。
     * <p>实测教训：只给卡名+效果号时，模型会自己臆测"对方这个效果没什么用"从而漏断，
     * 所以每一环都附上该卡效果文本（含发动条件）。
     */
    private void appendChainBlock(StringJoiner sj, DecisionPoint point, ChoiceRequest.ChainRequest request) {
        List<ChainNode> chain = point.state().currentChain();
        if (chain.isEmpty()) {
            sj.add("当前连锁：无（本次时点还没有发动）");
        } else {
            // 链必须完整：每一环都要在。缺环 → 模型会自己补因果（实机：链上少了被无效的那一环，
            // 模型就断言"对方那个效果已被我方无效"，从而把一个还会结算的展开当成已经断了）。
            sj.add("当前连锁（" + chain.size() + " 环；结算次序 " + resolveOrder(chain)
                    + "，后发的先结算）：");
            for (ChainNode node : chain) {
                long passcode = node.identity().passcode();
                sj.add("  #" + node.index() + " "
                        + (node.controller() == PlayerSide.SELF ? "我方" : "对方") + " "
                        + DuelMathService.labelOf(new CardInstance(0, node.identity(), node.source(),
                                null, null, null), facts)
                        + "（" + ZoneCodes.describe(node.source()) + "）"
                        + (node.negated() ? "  ← 已被无效：这一环不会生效" : ""));
                // 关键：明确"这一环发动的是哪一条效果"。完整条款不打折：
                // 以前只给"从 ① 起的前 120 字"，多效果卡的后面几条会被整段切掉：
                // 实机事故——对方「王之仆人-黑魔术师」发动 ③（对方场上魔陷全部破坏），
                // 上下文只有 ①②（当作黑魔术师 / 手卡特召），模型看不到任何"破坏"，
                // 于是判"弃权保留两张速攻与机关"，而这一环结算后正是把这三张全部破坏。
                List<String> clauses = cardText.clauses(passcode);
                Optional<String> fired = cardText.clauseOf(clauses, node.effectDescription());
                // 对象必须点名：卡面只写"以…为对象"，不写是哪张。
                // 实机：黑魔导阵②"以对方场上1张卡为对象除外"，模型自己补成"要除外我方燎里"，
                // 实际对象是我方后场的速攻魔法——猜错对象就会选错应对牌。
                if (!node.targets().isEmpty()) {
                    sj.add("      对象：" + targetLabels(node.targets(), point.state()));
                } else if (fired.isPresent() && fired.get().contains("为对象")) {
                    sj.add("      对象：协议未给出——不要猜是哪张卡；按最坏情况准备"
                            + "（宁可保住关键怪兽/后场，不要假设它只打小牌）");
                }
                if (fired.isPresent()) {
                    sj.add("      ★ 本次发动的是：" + clip(fired.get(), 800));
                    appendOtherClauses(sj, clauses, fired.get());
                } else if (!clauses.isEmpty()) {
                    sj.add("      该卡效果（协议未标明本次发动的是哪一条，按实际会造成的后果判断）：");
                    for (String clause : clauses) {
                        sj.add("        " + CardText.brief(clause));
                    }
                } else {
                    sj.add("      （卡库无此卡资料：不要凭印象编它的效果，必要时调 find_card 查）");
                }
            }
            sj.add("  ↑ 以上是**已发动、尚未结算**的效果：你选弃权就意味着它们照常结算。"
                    + "按上面的结算次序从最后一环往前推，逐个问「轮到我这一环时，它还在吗、还有效吗」。");
        }
        // 候选是引擎按规则校验过的合法动作；模型只需要判断价值，不要替引擎"验条件"
        sj.add("（下列候选均已通过规则校验：不必再判断能否发动，只判断值不值得）");
    }

    /** 把对象区域翻成"卡名（位置）"；查不到卡就给位置，绝不编名字。 */
    private String targetLabels(List<ZoneRef> targets, DuelState state) {
        StringJoiner sj = new StringJoiner("、");
        for (ZoneRef zone : targets) {
            String name = state.cards().values().stream()
                    .filter(card -> zone.equals(card.zone()))
                    .findFirst()
                    .map(card -> DuelMathService.labelOf(card, facts))
                    .orElse("（该位置的卡）");
            sj.add(name + "（" + ZoneCodes.describe(zone) + "）");
        }
        return sj.toString();
    }

    /** 链的结算次序（逆算）：#n → #n-1 → … → #1。写出来比让模型自己推更可靠。 */
    private static String resolveOrder(List<ChainNode> chain) {
        StringBuilder sb = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append('#').append(chain.get(i).index());
        }
        return sb.toString();
    }

    /** 可连锁效果：标 [必发]、带效果文本前 60 字（判断要不要投入，得看它到底做什么）。 */
    private void appendChainEffects(StringJoiner sj, DecisionPoint point, ChoiceRequest.ChainRequest request) {
        List<Candidate.EffectC> effects = point.candidatesOf(Candidate.EffectC.class);
        if (effects.isEmpty()) {
            return;
        }
        List<ChoiceRequest.ChainRequest.ChainOption> options = request.options();
        StringJoiner items = new StringJoiner("；");
        for (int i = 0; i < effects.size(); i++) {
            Candidate.EffectC e = effects.get(i);
            StringBuilder one = new StringBuilder(e.id().value()).append(' ')
                    .append(effectCardLabel(e.value().card()));
            if (i < options.size() && options.get(i).forced()) {
                one.append(" [必发]");
            }
            one.append("：").append(
                    cardText.fired(e.value().card().identity().passcode(), e.value().description()));
            items.add(one.toString());
        }
        sj.add("可连锁：" + items);
    }

    private String effectCardLabel(ChoiceRequest.CardChoice card) {
        return DuelMathService.labelOf(instanceOf(card), facts);
    }

    /** 效果文的分条标记（多效果卡）。 */
    private static final String CLAUSE_MARKS = "①②③④⑤⑥⑦⑧";

    /** 除"本次发动"那条以外的其他条款（避免上下文里同一句话出现两遍）。 */
    private void appendOtherClauses(StringJoiner sj, List<String> clauses, String fired) {
        List<String> others = clauses.stream().filter(c -> !c.equals(fired)).toList();
        if (others.isEmpty()) {
            return;
        }
        sj.add("      该卡其他效果（判断它后续还能做什么时看这里）：");
        for (String clause : others) {
            sj.add("        " + CardText.brief(clause));
        }
    }

    /** 候选卡面预算。 */
    private static final int CANDIDATE_FACT_MAX_CARDS = 6;
    private static final int CANDIDATE_FACT_TOTAL_LIMIT = 1_200;

    /**
     * {@code [候选卡面]}：本次可选的卡的效果摘要。
     *
     * <p>为什么需要：候选行只有"名字（种类）"，讲不出"选了会不会白费"。
     * 实战事故——零露②要求"破坏场上 1 张卡"，模型把对方「超魔导龙骑士」选了进去，
     * 而那张卡 ① 明写『这张卡不会被效果破坏，双方不能把这张卡作为效果的对象』，
     * 整次除去直接白给（同一局它自己在上一句还写着"破坏龙骑士以外的卡"）。
     *
     * <p>只给候选里的卡、且逐条列（不只第一条），总量有上限——决策包每回合要发几十次，不能无限长。
     */
    private void appendCandidateCardFacts(StringJoiner sj, DecisionPoint point) {
        List<ChoiceRequest.CardChoice> choices = new ArrayList<>();
        point.candidatesOf(Candidate.CardC.class).forEach(c -> choices.add(c.value()));
        point.candidatesOf(Candidate.EffectC.class).forEach(c -> choices.add(c.value().card()));
        point.candidatesOf(Candidate.TributeC.class).forEach(c -> choices.add(c.value().card()));
        if (choices.isEmpty()) {
            return;
        }
        LinkedHashMap<Long, String> cards = new LinkedHashMap<>();
        for (ChoiceRequest.CardChoice card : choices) {
            long passcode = card.identity() == null ? 0 : card.identity().passcode();
            if (passcode <= 0 || cards.containsKey(passcode)) {
                continue;
            }
            List<String> clauses = cardText.clauses(passcode);
            if (clauses.isEmpty()) {
                continue;
            }
            cards.put(passcode, "- " + cardText.card(passcode) + "：" + cardText.all(clauses, 320));
        }
        if (cards.isEmpty()) {
            return;
        }
        sj.add("[候选卡面] 本次可选的卡效果——选「破坏/无效/除外」的目标前先看这里："
                + "写明『不会被效果破坏』『不能作为效果的对象』『不受效果影响』的卡，选了等于白付代价");
        int used = 0;
        int count = 0;
        for (String line : cards.values()) {
            if (count >= CANDIDATE_FACT_MAX_CARDS || used > CANDIDATE_FACT_TOTAL_LIMIT) {
                sj.add("  …（其余候选项的卡面略）");
                break;
            }
            sj.add("  " + line);
            used += line.length();
            count++;
        }
    }

    /**
     * {@code [本回合可用性]}：把手牌里"这一回合发不出来的魔法卡"直接点名。
     *
     * <p>为什么由代码说：这是**规则事实**，不是判断——手牌里的魔法卡（速攻/通常/永续/装备/场地/仪式）
     * 在对手回合发不出来；连"留到对手回合用"都不成立。模型已经在这条上错了三次
     * （不盖抓锚 → 对手回合空转 → 自己回合为了用掉又乱发），每次都要靠人发现。
     * 只列魔法卡、不列陷阱：陷阱里有"可以从手牌发动"的例外，点错了比不说更糟。
     */
    private void appendHandSpellUsability(StringBuilder sb, DuelState state) {
        List<CardInstance> hand = state.player(PlayerSide.SELF).hand();
        List<String> spells = new ArrayList<>();
        for (CardInstance card : hand) {
            if (card.identity() == null || card.identity().passcode() <= 0) {
                continue;
            }
            facts.byPasscode(card.identity().passcode())
                    .filter(fact -> !fact.isMonster())
                    .map(CardFact::cardType)
                    .filter(type -> type != null && type.contains("魔法"))
                    .ifPresent(type -> spells.add(DuelMathService.labelOf(card, facts) + "〔" + type + "〕"));
        }
        if (spells.isEmpty()) {
            return;
        }
        sb.append("[本回合可用性] ").append(String.join("、", spells)).append("：");
        if (state.isMyTurn()) {
            sb.append("魔法卡只能在自己回合的主要阶段发动。留到对手回合 = 用不了；")
                    .append("要拿它当对手回合的阻抗，必须在结束回合前盖到魔陷区。\n");
        } else {
            sb.append("现在是对手回合，手牌里的魔法卡一律发不出来（只有已盖的速攻魔法/陷阱、")
                    .append("卡面写明可从手牌发动的怪兽才算阻抗）。\n");
        }
    }

    private String handLine(DuelState state, PlayerSide side) {
        List<CardInstance> hand = state.player(side).hand();
        if (hand.isEmpty()) {
            return "（无）";
        }
        StringJoiner sj = new StringJoiner("、");
        // 带种类标记：手牌里的〔速攻魔法〕〔通常陷阱〕和〔怪兽/手坑〕在"对手回合能不能用"上
        // 完全不同，而这个区别恰恰决定"要不要现在盖下去"。不标的话模型只看到卡名，
        // 就会把手里那张速攻当成对手回合的阻抗（实机反复出现）。
        hand.forEach(c -> sj.add(DuelMathService.labelOf(c, facts) + typeTagOf(c)));
        return sj.toString();
    }

    /** 手牌/场上卡的种类标记（非怪兽卡才有）。 */
    private String typeTagOf(CardInstance card) {
        if (card == null || card.identity() == null || card.identity().passcode() <= 0) {
            return "";
        }
        return facts.byPasscode(card.identity().passcode())
                .filter(fact -> !fact.isMonster())
                .map(CardFact::cardType)
                .filter(type -> type != null && !type.isBlank())
                .map(type -> "〔" + type + "〕")
                .orElse("");
    }

    /**
     * [场地槽位]：逐格列出双方怪兽区/魔陷区/场地/额外怪兽区的占用，编码与位置决策里的编号一致。
     *
     * <p>没有这一块，"位置"就没法成为决策：模型只知道"有 5 个空格"，
     * 不知道哪格空着、自己场上的卡在第几格、额外怪兽区占没占——它只能瞎写或干脆不写。
     * 列的是**格子**而不是"一堆卡名"，因为位置决策问的就是格子。
     */
    private String boardSlotsSection(DuelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[场地槽位] 编码即位置（序号从 1 起；空 = 可放置）。写计划 zone 或选位置时用编码，如 MMZ-3：\n");
        for (PlayerSide side : List.of(PlayerSide.SELF, PlayerSide.OPPONENT)) {
            String who = side == PlayerSide.SELF ? "我方" : "对方";
            sb.append("  ").append(who)
                    .append(" MMZ: ").append(slots(state, side, ZoneKind.MAIN_MONSTER_ZONE, 5))
                    .append(" ｜ EMZ: ").append(slots(state, side, ZoneKind.EXTRA_MONSTER_ZONE, 2))
                    .append(" ｜ FZ: ").append(slotName(state, new ZoneRef(ZoneKind.FIELD_ZONE, side, 0)))
                    .append("\n");
            sb.append("       STZ: ").append(slots(state, side, ZoneKind.SPELL_TRAP_ZONE, 5))
                    .append(" ｜ PZ: ").append(slots(state, side, ZoneKind.PENDULUM_ZONE, 2))
                    .append("\n");
        }
        return sb.toString();
    }

    private String slots(DuelState state, PlayerSide side, ZoneKind kind, int count) {
        StringJoiner sj = new StringJoiner(" ");
        for (int seq = 0; seq < count; seq++) {
            sj.add((seq + 1) + slotName(state, new ZoneRef(kind, side, seq)));
        }
        return sj.toString();
    }

    /** 单格：空 / 卡名（里侧只说"盖卡"，不编造身份）。 */
    private String slotName(DuelState state, ZoneRef zone) {
        return state.cardAt(zone).map(c -> {
            if (c.isUnknown()) {
                return "盖卡";
            }
            return facts.byPasscode(c.passcode()).map(f -> clip(f.name(), 10))
                    .orElseGet(() -> "卡(" + c.passcode() + ")");
        }).orElse("空");
    }

    private String fieldLine(DuelState state, PlayerSide side) {
        StringJoiner sj = new StringJoiner("；");
        List<CardInstance> monsters = state.monsters(side);
        if (monsters.isEmpty()) {
            sj.add("怪兽区（空）");
        } else {
            StringJoiner mj = new StringJoiner("、");
            monsters.forEach(c -> mj.add(monsterLabel(c)));
            sj.add("怪兽：" + mj);
        }
        List<CardInstance> backrow = state.spellTraps(side);
        if (backrow.isEmpty()) {
            sj.add("魔陷区（空）");
        } else {
            StringJoiner bj = new StringJoiner("、");
            backrow.forEach(c -> {
                boolean known = !c.isUnknown();
                String name = known ? DuelMathService.labelOf(c, facts) : "盖卡";
                if (c.position() == null) {
                    // 朝向未同步：别硬说里侧（表侧永续魔陷很常见），如实标注
                    bj.add(known ? name + "[朝向未知]" : "盖卡");
                } else if (c.position().isFaceUp()) {
                    bj.add(name);
                } else {
                    bj.add(known ? name + "(里侧)" : "盖卡");
                }
            });
            sj.add("魔陷：" + bj);
        }
        state.fieldSpell(side).ifPresent(field ->
                sj.add("场地：" + DuelMathService.labelOf(field, facts)));
        return sj.toString();
    }

    private String monsterLabel(CardInstance c) {
        if (c.position() == null) {
            // 表示形式未同步：身份已知也别猜"里侧"，如实标注
            return c.isUnknown() ? "盖卡怪"
                    : DuelMathService.labelOf(c, facts) + "[状态未知]";
        }
        if (!c.position().isFaceUp()) {
            return c.isUnknown() ? "盖卡怪"
                    : DuelMathService.labelOf(c, facts) + "[里侧]";
        }
        CardFact fact = facts.byPasscode(c.passcode()).orElse(null);
        String pos = c.position() == Position.FACE_UP_ATTACK ? "攻" : "守";
        if (fact == null) {
            return "卡(" + c.passcode() + ")[" + pos + "]";
        }
        String stats = fact.atk() == null ? "?" : String.valueOf(fact.atk());
        String def = fact.defense() == null ? "?" : String.valueOf(fact.defense());
        return fact.name() + "[" + pos + " ATK" + stats + "/DEF" + def + "]";
    }

    private String graveLine(DuelState state, PlayerSide side) {
        List<CardIdentity> gy = state.player(side).graveyard();
        if (gy.isEmpty()) {
            return "墓地（空）";
        }
        StringJoiner sj = new StringJoiner("、");
        gy.forEach(id -> sj.add(DuelMathService.labelOf(
                new CardInstance(0, id, null, null, null, null), facts)));
        int banished = state.player(side).banished().size();
        return sj + "；除外 " + banished + " 张";
    }

    private String cardLabel(Candidate.CardC card) {
        return card.id().value() + " " + DuelMathService.labelOf(instanceOf(card.value()), facts)
                + typeTag(card.value());
    }

    /**
     * 非怪兽卡补一个种类标记（{@code 〔永续魔法〕〔速攻魔法〕〔通常陷阱〕}…）。
     *
     * <p>不标这一条，模型判不出"这张盖下去能不能在对手回合发动"——实战里它把
     * {@code 永续魔法} 当阻抗盖下去，还写进回合计划当第一步，结果这一步永远执行不了。
     * 卡库里本来就有 {@code card_type}，只是以前没进决策上下文。
     */
    private String typeTag(ChoiceRequest.CardChoice choice) {
        if (choice == null || choice.identity() == null || choice.identity().passcode() <= 0) {
            return "";
        }
        return facts.byPasscode(choice.identity().passcode())
                .filter(fact -> !fact.isMonster())
                .map(CardFact::cardType)
                .filter(type -> type != null && !type.isBlank())
                .map(type -> "〔" + type + "〕")
                .orElse("");
    }

    private String attackLabel(ChoiceRequest.AttackChoice attack) {
        CardFact fact = facts.byPasscode(attack.card().identity().passcode()).orElse(null);
        String name = fact == null ? "卡(" + attack.card().identity().passcode() + ")" : fact.name();
        return name + (attack.directAttack() ? "（可直击）" : "");
    }

    private static CardInstance instanceOf(ChoiceRequest.CardChoice choice) {
        return new CardInstance(choice.instanceId(), choice.identity(), choice.zone(), null, null, null);
    }

    private String zoneLabel(Candidate.ZoneC zone) {
        return zone.id().value() + " " + ZoneCodes.label(zone.value())
                + "（" + ZoneCodes.code(zone.value()) + "）";
    }

    private <T extends Candidate> void appendGroup(StringJoiner sj, String title, List<T> candidates,
                                                   Function<T, String> label) {
        if (candidates.isEmpty()) {
            return;
        }
        StringJoiner items = new StringJoiner("、");
        candidates.forEach(c -> items.add(label.apply(c)));
        sj.add(title + "：" + items);
    }

    private void appendEffectGroup(StringJoiner sj, String title, List<Candidate.EffectC> effects) {
        if (effects.isEmpty()) {
            return;
        }
        StringJoiner items = new StringJoiner("；");
        effects.forEach(e -> items.add(e.id().value() + " "
                + DuelMathService.labelOf(instanceOf(e.value().card()), facts)
                + "： " + cardText.fired(e.value().card().identity().passcode(),
                        e.value().description())));
        sj.add(title + "：" + items);
    }

    /** 弃权类候选（不响应/不发动/否/取消/完成选择）：它们现在有真编号，不再写死成 C0。 */
    private void appendDeclines(StringJoiner sj, DecisionPoint point) {
        List<Candidate.DeclineC> declines = point.candidatesOf(Candidate.DeclineC.class);
        if (declines.isEmpty()) {
            return;
        }
        StringJoiner items = new StringJoiner("、");
        declines.forEach(decline -> items.add(decline.id().value() + " " + decline.kind().label()));
        sj.add("弃权项：" + items);
    }

    /**
     * 阶段动作（进战阶/结束回合/进 M2/结束战斗/洗手牌）一律带编号。
     *
     * <p>没有编号时模型想"结束回合"只能填 C0，而 C0 只在连锁/是否发动里存在 → 判越界 → 降级经验规则。
     */
    private void appendFlags(StringJoiner sj, DecisionPoint point) {
        List<Candidate.FlagC> flags = point.candidatesOf(Candidate.FlagC.class);
        if (flags.isEmpty()) {
            return;
        }
        String endTurnNote = endTurnNote(point);
        StringJoiner items = new StringJoiner("、");
        flags.forEach(flag -> items.add(flag.id().value() + " " + flag.flag().label()
                + (flag.flag() == Candidate.Flag.END_TURN ? endTurnNote : "")));
        sj.add("阶段动作：" + items);
    }

    /**
     * {@code [收尾审计]}：这一步可以结束回合时，把"还摆着哪几类可选动作"逐类摊开。
     *
     * <p>为什么需要：模型结束回合时常常只回答"当前没有净收益的动作"，但它**并没有逐项看过**
     * 候选——实机里计划写了"把抓锚与双纽闪门盖下去"，它却在主阶段直接结束回合，
     * 理由只谈特召与格子，连"盖放魔陷"这一行有 3 个候选都没提。
     *
     * <p>所以把"还没评估的类别"直接列在结束回合旁边：这是候选集的另一种排法（事实），
     * 不是替它做判断。要求它逐类给出放弃理由，才能算真的评估过。
     */
    private void appendEndTurnAudit(StringJoiner sj, DecisionPoint point, ChoiceRequest.MainPhaseRequest m) {
        boolean canEndTurn = point.candidatesOf(Candidate.FlagC.class).stream()
                .anyMatch(flag -> flag.flag() == Candidate.Flag.END_TURN);
        if (!canEndTurn || !point.state().isMyTurn()) {
            return;
        }
        List<String> groups = new ArrayList<>();
        addGroupLine(groups, "通常召唤", m.normalSummonable());
        addGroupLine(groups, "特殊召唤/连接召唤", m.specialSummonable());
        addGroupLine(groups, "变更表示形式", m.repositionable());
        addGroupLine(groups, "盖放怪兽", m.monsterSettable());
        addGroupLine(groups, "盖放魔陷", m.spellTrapSettable());
        List<Candidate.EffectC> effects = point.candidatesOf(Candidate.EffectC.class);
        if (!effects.isEmpty()) {
            groups.add("发动效果 " + effects.size() + " 项：" + names(effects.stream()
                    .map(e -> e.value().card()).toList()));
        }
        if (groups.isEmpty()) {
            return;
        }
        sj.add("[收尾审计] 这一步仍可以结束回合；结束前请逐类确认你已经评估并主动放弃：");
        groups.forEach(group -> sj.add("  - " + group));
        sj.add("  哪一类你还没看过 → 先回去看，不要直接结束回合；都看过的，在 verify 里逐类写一句放弃理由。");
    }

    private void addGroupLine(List<String> out, String title, List<ChoiceRequest.CardChoice> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        out.add(title + " " + cards.size() + " 项：" + names(cards));
    }

    /** 最多列 4 个名字，其余用"…"收尾（审计要的是"有没有看过"，不是完整清单）。 */
    private String names(List<ChoiceRequest.CardChoice> cards) {
        StringJoiner sj = new StringJoiner("、");
        int limit = Math.min(cards.size(), 4);
        for (int i = 0; i < limit; i++) {
            sj.add(DuelMathService.labelOf(instanceOf(cards.get(i)), facts));
        }
        return cards.size() > limit ? sj + "…" : sj.toString();
    }

    /**
     * "结束回合"这个选项的附注：手上还有几张能盖的速攻/陷阱没盖。
     *
     * <p>为什么挂在选项上：这是"最后一刻"的位置。实测模型会在计划里写"把抓锚盖下去"，
     * 到了主阶段却选"结束回合"，理由只谈特召与格子——因为"盖放"没有即时收益，
     * 而"不要为用而用"这条纪律又把它推向结束。把成本/收益直接印在选项上，
     * 它做选择时就不可能"没看见"。
     *
     * <p>只数 {@code 速攻魔法} 与 {@code 陷阱}：这两类盖下去确实能在对手回合用；
     * 通常/永续/装备/场地/仪式魔法盖了也用不了，数进来只会误导。
     */
    private String endTurnNote(DecisionPoint point) {
        if (!point.state().isMyTurn()) {
            return "";
        }
        List<String> usable = new ArrayList<>();
        for (CardInstance card : point.state().player(PlayerSide.SELF).hand()) {
            if (card.identity() == null || card.identity().passcode() <= 0) {
                continue;
            }
            String type = facts.byPasscode(card.identity().passcode())
                    .map(CardFact::cardType).orElse(null);
            if (type == null) {
                continue;
            }
            if (type.contains("速攻") || type.contains("陷阱")) {
                usable.add(DuelMathService.labelOf(card, facts) + "〔" + type + "〕");
            }
        }
        if (usable.isEmpty()) {
            return "";
        }
        return "（提醒：手上还有 " + usable.size() + " 张速攻/陷阱没盖——" + String.join("、", usable)
                + "。结束回合后它们在对手回合用不了；盖放不消耗卡，只是把它们变成对手回合能用的牌）";
    }
}
