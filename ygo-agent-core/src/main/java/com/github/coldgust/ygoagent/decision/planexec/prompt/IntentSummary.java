package com.github.coldgust.ygoagent.decision.planexec.prompt;

import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.plan.ZoneCodes;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;

import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把"已经提交出去的一手"压成一句人话，用于给下一次决策交代上下文。
 *
 * <p>为什么需要：one-shot 每手都是<b>独立单轮调用</b>（system + 当前局面），它看不到自己刚才
 * 做过什么、也不知道自己上一手说过什么。于是常见两种自相矛盾：
 * <ul>
 *   <li>上一手判"保留零露作终场阻抗"，几十秒后另一手把它解放掉；</li>
 *   <li>上一手写"泡影要留给对方上级怪兽"，下一手在检索环节就交了。</li>
 * </ul>
 * 这不是模型"健忘"，是上下文里根本没有这段历史。
 *
 * <p>这里只做<b>事实摘要</b>（选了什么、放到哪），不做评价——评价属于 rationale。
 */
public final class IntentSummary {

    private final CardText cardText;

    public IntentSummary(CardFacts facts) {
        this.cardText = new CardText(facts);
    }

    /** 一句短语，如"通常召唤 闪刀姬-零衣(26077387) → MMZ-3"。 */
    public String of(ChoiceIntent intent, DecisionPoint point) {
        if (intent == null) {
            return "";
        }
        return switch (intent) {
            case ChoiceIntent.Summon s -> "通常召唤 " + card(s.card(), point);
            case ChoiceIntent.SpecialSummon s -> "特殊召唤 " + card(s.card(), point);
            case ChoiceIntent.SetMonster s -> "盖放怪兽 " + card(s.card(), point);
            case ChoiceIntent.SetSpellTrap s -> "盖放魔陷 " + card(s.card(), point);
            case ChoiceIntent.Reposition r -> "改变表示形式 " + card(r.card(), point);
            case ChoiceIntent.Activate a -> "发动 " + effect(a.effect(), point);
            case ChoiceIntent.BattleActivate b -> "战斗中发动 " + effect(b.effect(), point);
            case ChoiceIntent.Attack a -> "攻击宣言 " + card(a.attack(), point);
            case ChoiceIntent.ChainRespond r -> "连锁发动 " + effect(r.option(), point);
            case ChoiceIntent.ChainDecline ignored -> "不响应（弃权）";
            case ChoiceIntent.EffectYesNo y -> y.activate() ? "发动该效果" : "不发动该效果";
            case ChoiceIntent.YesNo y -> y.yes() ? "确认：是" : "确认：否";
            case ChoiceIntent.EnterBattlePhase ignored -> "进战阶";
            case ChoiceIntent.EndTurn ignored -> "结束回合";
            case ChoiceIntent.EnterMainPhase2 ignored -> "进 M2";
            case ChoiceIntent.EndBattlePhase ignored -> "结束战斗阶段";
            case ChoiceIntent.ShuffleHand ignored -> "洗手牌";
            case ChoiceIntent.PositionChoice p -> "表示形式：" + p.position();
            case ChoiceIntent.Place p -> "选位置 " + zones(p.zones(), point);
            case ChoiceIntent.Select s -> "选卡 " + cards(s.cards(), point);
            case ChoiceIntent.Tribute t -> "选祭品 " + cards(t.cards(), point);
            case ChoiceIntent.Sum u -> "凑数选择 " + cards(u.cards(), point);
            case ChoiceIntent.UnselectSelect u -> "选卡 " + card(u.card(), point);
            case ChoiceIntent.UnselectUndo u -> "退回 " + card(u.card(), point);
            case ChoiceIntent.UnselectFinish ignored -> "完成选择";
            case ChoiceIntent.Cancel ignored -> "取消";
            case ChoiceIntent.Option o -> "选选项 #" + o.index();
            case ChoiceIntent.DeclareRace r -> "宣言种族 " + r.races();
            case ChoiceIntent.DeclareAttribute a -> "宣言属性 " + a.attributes();
            case ChoiceIntent.DeclareCard c -> "宣言卡片 " + cardText.card(c.passcode());
            case ChoiceIntent.DeclareNumber n -> "宣言数值 #" + n.index();
            case ChoiceIntent.Sort s -> "排序";
            case ChoiceIntent.Counter c -> "取除指示物";
        };
    }

    private String card(CandidateId id, DecisionPoint point) {
        return point.candidate(id)
                .map(this::label)
                .map(text -> text + zoneSuffix(id, point))
                .orElseGet(() -> id.value());
    }

    private String effect(CandidateId id, DecisionPoint point) {
        return point.candidate(id)
                .filter(Candidate.EffectC.class::isInstance)
                .map(Candidate.EffectC.class::cast)
                .map(e -> cardText.effectLine(e.value().card().identity().passcode(),
                        e.value().description()))
                .orElseGet(() -> card(id, point));
    }

    private String cards(List<CandidateId> ids, DecisionPoint point) {
        return ids.stream().map(id -> card(id, point))
                .collect(Collectors.joining("、"));
    }

    private String zones(List<CandidateId> ids, DecisionPoint point) {
        return ids.stream()
                .map(id -> point.candidate(id)
                        .filter(Candidate.ZoneC.class::isInstance)
                        .map(Candidate.ZoneC.class::cast)
                        .map(z -> ZoneCodes.code(z.value()))
                        .orElseGet(id::value))
                .collect(Collectors.joining("、"));
    }

    /** 卡/效果候选的显示名：名字（种类）。 */
    private String label(Candidate candidate) {
        return switch (candidate) {
            case Candidate.CardC c -> cardText.card(c.value().identity().passcode());
            case Candidate.EffectC e -> cardText.card(e.value().card().identity().passcode());
            case Candidate.AttackC a -> cardText.card(a.value().card().identity().passcode());
            case Candidate.TributeC t -> cardText.card(t.value().card().identity().passcode());
            case Candidate.ZoneC z -> ZoneCodes.code(z.value());
            case Candidate.FlagC f -> f.flag().label();
            case Candidate.DeclineC d -> d.kind().label();
            case Candidate.CounterC c -> "指示物";
        };
    }

    /** 动作若落在场上，补一句落点（位置是决策的一部分，事后要能对上）。 */
    private String zoneSuffix(CandidateId id, DecisionPoint point) {
        return point.candidate(id)
                .map(candidate -> switch (candidate) {
                    case Candidate.CardC c -> Optional.ofNullable(c.value().zone())
                            .filter(ZoneCodes::isBoardSlot)
                            .map(z -> " → " + ZoneCodes.code(z))
                            .orElse("");
                    case Candidate.ZoneC z -> "";
                    default -> "";
                })
                .orElse("");
    }

}
