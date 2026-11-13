package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.context.CardText;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.support.Texts;

/**
 * 候选段的渲染：按请求类型把候选翻成"实际能做什么"的分组清单，并附候选卡面与收尾审计。
 * 候选行是模型投票的唯一依据：编号真实、卡名真实、效果原文可定位。
 */
final class CandidateSections {

    private static final int CANDIDATE_FACT_MAX_CARDS = 6;
    private static final int CANDIDATE_FACT_TOTAL_LIMIT = 1_200;

    private final CardFacts facts;
    private final CardText cardText;

    CandidateSections(CardFacts facts, CardText cardText) {
        this.facts = facts;
        this.cardText = cardText;
    }

    String candidatesSection(DecisionPoint point) {
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
            long passcode = y.card().identity().passcode();
            Optional<String> firedClause = cardText.clauseOf(passcode, y.description());
            sj.add("是否发动：" + effectCardLabel(y.card())
                    + (firedClause.isPresent()
                    ? " —— 这次要发动的是：" + CardText.brief(firedClause.get())
                    : ""));
            if (firedClause.isEmpty()) {
                appendUnlabeledEffectClauses(sj, passcode);
            }
            // 这一句是必须的：引擎只有在"这个效果此刻可以发动"时才会发这个请求。
            sj.add("  ↑ 引擎只在「这个效果此刻可以发动」时才会问这一句：这是**要不要发**，不是**能不能发**。"
                    + "不要在理由里写「条件不满足 / 无法发动」——那说明你记错了规则，此时请重读上面的效果原文。");
            // 候选行只给编号 + 卡名：条款在上面已经列过（解不出时是全部条款），
            // 再渲染一遍只会让模型顺着第一条读下去。
            appendGroup(sj, "发动", point.candidatesOf(Candidate.EffectC.class),
                    e -> e.id().value() + " " + effectCardLabel(e.value().card()));
            appendDeclines(sj, point);
        } else if (request instanceof ChoiceRequest.YesNoRequest y) {
            sj.add("是/否确认（description 码 " + y.description() + "）");
            appendDeclines(sj, point);
        } else if (request instanceof ChoiceRequest.UnselectRequest u) {
            // 这一步几乎总是"上一个动作的后续"（选素材/选送墓/选保留），不是独立决策点。
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
            sj.add(point.kind() + "：候选 " + point.candidates().size() + " 个（详情走 Reflex/直答）");
        }
        appendCandidateCardFacts(sj, point);
        return sj.toString();
    }


    /**
     * 主阶段的卡类候选按"实际能做什么"分组。
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
     */
    private void appendChainBlock(StringJoiner sj, DecisionPoint point, ChoiceRequest.ChainRequest request) {
        List<ChainNode> chain = point.state().currentChain();
        if (chain.isEmpty()) {
            sj.add("当前连锁：无（本次时点还没有发动）");
        } else {
            sj.add("当前连锁（" + chain.size() + " 环；结算次序 " + resolveOrder(chain)
                    + "，后发的先结算）：");
            for (ChainNode node : chain) {
                long passcode = node.identity().passcode();
                sj.add("  #" + node.index() + " "
                        + (node.controller() == PlayerSide.SELF ? "我方" : "对方") + " "
                        + CardLabels.labelOf(new CardInstance(0, node.identity(), node.source(),
                                null, null, null), facts)
                        + "（" + ZoneCodes.describe(node.source()) + "）"
                        + (node.negated() ? "  ← 已被无效：这一环不会生效" : ""));
                List<String> clauses = cardText.clauses(passcode);
                Optional<String> fired = cardText.clauseOf(passcode, clauses, node.effectDescription());
                // 对象必须点名：卡面只写"以…为对象"，不写是哪张。
                if (!node.targets().isEmpty()) {
                    sj.add("      对象：" + targetLabels(node.targets(), point.state()));
                } else if (fired.isPresent() && fired.get().contains("为对象")) {
                    sj.add("      对象：协议未给出——不要猜是哪张卡；按最坏情况准备"
                            + "（宁可保住关键怪兽/后场，不要假设它只打小牌）");
                }
                if (fired.isPresent()) {
                    sj.add("      ★ 本次发动的是：" + Texts.flattenClip(fired.get(), 800));
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
                    .map(card -> CardLabels.labelOf(card, facts))
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
        return CardLabels.labelOf(instanceOf(card), facts);
    }

    private void appendUnlabeledEffectClauses(StringJoiner sj, long passcode) {
        List<String> clauses = cardText.clauses(passcode);
        if (clauses.isEmpty()) {
            sj.add("  （卡库没有这张卡的效果原文——不要凭印象编造，必要时调 find_card 查）");
            return;
        }
        sj.add("  协议**没有标出**这次是哪一条效果（「是否发动」的确认号不是效果序号）：下面把全部条款"
                + "（①②③…）列出来，按排除法定位——");
        for (String clause : clauses) {
            sj.add("    " + CardText.brief(clause));
        }
        sj.add("  定位方法：① 本回合已经发动过的效果（见 [本回合已做]）不可能是本次请求，先排除；"
                + "② 再看哪一条的发动时点与当前时点吻合（刚召唤·特殊召唤成功 / 刚进墓地 / 刚被破坏·除外 / "
                + "刚有卡加入手牌…）；③ 不要默认按 ① 的代价与收益来判断，按你定位到的那一条算净收益。");
    }

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

    /**
     * {@code [候选卡面]}：本次可选的卡的效果摘要。
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

    private String cardLabel(Candidate.CardC card) {
        return card.id().value() + " " + CardLabels.labelOf(instanceOf(card.value()), facts)
                + typeTag(card.value());
    }

    /**
     * 非怪兽卡补一个种类标记（{@code 〔永续魔法〕〔速攻魔法〕〔通常陷阱〕}…）。
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
                + CardLabels.labelOf(instanceOf(e.value().card()), facts)
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
            sj.add(CardLabels.labelOf(instanceOf(cards.get(i)), facts));
        }
        return cards.size() > limit ? sj + "…" : sj.toString();
    }

    /**
     * "结束回合"这个选项的附注：手上还有几张能盖的速攻/陷阱没盖。
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
                usable.add(CardLabels.labelOf(card, facts) + "〔" + type + "〕");
            }
        }
        if (usable.isEmpty()) {
            return "";
        }
        return "（提醒：手上还有 " + usable.size() + " 张速攻/陷阱没盖——" + String.join("、", usable)
                + "。结束回合后它们在对手回合用不了；盖放不消耗卡，只是把它们变成对手回合能用的牌）";
    }
}
