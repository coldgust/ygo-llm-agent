package com.github.coldgust.ygoagent.pipeline.reflex;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.reflex.ReflexPolicy;
import com.github.coldgust.ygoagent.support.RequestQuirks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * REFLEX = <b>零策略假设层</b>：只回答"无论玩家的战略如何、答案都相同"的请求；
 * 凡是有取舍的，一律返回 {@code empty}，交给 ②计划匹配 → ④直答 → ⑤兜底。
 */
public final class ReflexPolicyImpl implements ReflexPolicy {

    @Override
    public Optional<ChoiceIntent> tryAnswer(DecisionPoint point) {
        return switch (point.kind()) {
            case MAIN_PHASE, BATTLE_PHASE -> Optional.empty(); // 战略层，永远让行
            case CHAIN -> chain(point);
            case EFFECT_YES_NO -> Optional.empty();            // "是否发动"是资源与时机的判断
            case YES_NO -> Optional.of(new ChoiceIntent.YesNo(true)); // 只给 description 码，无文本可读
            case POSITION -> singlePosition(point);
            case PLACE, DISFIELD -> place(point);
            case CARD_SELECT -> cardSelect(point);
            case TRIBUTE_SELECT -> tribute(point);
            case SUM -> sum(point);
            case UNSELECT -> unselect(point);
            case OPTION -> option(point);
            case DECLARE_RACE -> declareRace(point);
            case DECLARE_ATTRIBUTE -> declareAttribute(point);
            case DECLARE_CARD -> Optional.of(new ChoiceIntent.DeclareCard(14558127L));
            case DECLARE_NUMBER -> declareNumber(point);
            case SORT -> sort(point);
            case COUNTER -> counter(point);
        };
    }


    private Optional<ChoiceIntent> chain(DecisionPoint point) {
        var request = (ChoiceRequest.ChainRequest) point.request();
        List<Candidate.EffectC> effects = point.candidatesOf(Candidate.EffectC.class);

        if (request.options().isEmpty()) {
            return Optional.of(new ChoiceIntent.ChainDecline());
        }
        if (!request.hasForcedEffect()) {
            return Optional.empty();
        }
        if (request.options().size() == 1 && !effects.isEmpty()) {
            return Optional.of(new ChoiceIntent.ChainRespond(effects.getFirst().id()));
        }
        return Optional.empty();
    }


    private Optional<ChoiceIntent> singlePosition(DecisionPoint point) {
        var request = (ChoiceRequest.PositionRequest) point.request();
        if (request.positions().size() != 1) {
            return Optional.empty(); // 攻/守是策略：攻击表示给对手战伤机会，守备表示放弃输出
        }
        Position only = request.positions().iterator().next();
        return Optional.of(new ChoiceIntent.PositionChoice(only));
    }


    private Optional<ChoiceIntent> place(DecisionPoint point) {
        int count = point.request() instanceof ChoiceRequest.PlaceRequest p
                ? p.count()
                : ((ChoiceRequest.DisfieldRequest) point.request()).count();
        List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        int take = RequestQuirks.placeZoneCount(count, zones.size());
        List<CandidateId> ids = zones.stream().limit(take).map(Candidate.ZoneC::id).toList();
        return Optional.of(new ChoiceIntent.Place(ids));
    }


    private Optional<ChoiceIntent> cardSelect(DecisionPoint point) {
        var request = (ChoiceRequest.CardSelectRequest) point.request();
        List<Candidate.CardC> cards = point.candidatesOf(Candidate.CardC.class);
        if (request.max() <= 0) {
            return Optional.of(new ChoiceIntent.Cancel());
        }
        if (request.min() == request.max() && request.min() >= cards.size()) {
            return Optional.of(new ChoiceIntent.Select(all(cards)));
        }
        return Optional.empty(); // 选哪张是策略（送墓/除外/检索的取舍）
    }

    private Optional<ChoiceIntent> tribute(DecisionPoint point) {
        var request = (ChoiceRequest.TributeSelectRequest) point.request();
        List<Candidate.TributeC> cards = point.candidatesOf(Candidate.TributeC.class);
        if (request.min() == request.max() && request.min() >= cards.size()) {
            return Optional.of(new ChoiceIntent.Tribute(
                    cards.stream().map(Candidate.TributeC::id).toList()));
        }
        return Optional.empty(); // 祭品取舍（解放哪只怪兽）是策略
    }

    private Optional<ChoiceIntent> sum(DecisionPoint point) {
        var request = (ChoiceRequest.SumRequest) point.request();
        List<Candidate.CardC> all = point.candidatesOf(Candidate.CardC.class);
        if (request.optional().isEmpty() && request.mustSelect().size() == all.size()) {
            return Optional.of(new ChoiceIntent.Sum(all(all)));
        }
        return Optional.empty();
    }

    private Optional<ChoiceIntent> unselect(DecisionPoint point) {
        var request = (ChoiceRequest.UnselectRequest) point.request();
        List<Candidate.CardC> selectable = point.candidatesOf(Candidate.CardC.class).stream()
                .filter(c -> !request.selected().contains(c.value()))
                .toList();
        if (selectable.isEmpty()) {
            return Optional.of(request.finishable()
                    ? new ChoiceIntent.UnselectFinish() : new ChoiceIntent.Cancel());
        }
        return Optional.empty();
    }


    private Optional<ChoiceIntent> option(DecisionPoint point) {
        // 选项只有"描述码"，没有可读文本：让行也不能让模型判断得更好，保留确定性
        return Optional.of(new ChoiceIntent.Option(0));
    }


    private Optional<ChoiceIntent> sort(DecisionPoint point) {
        List<Candidate.CardC> cards = point.candidatesOf(Candidate.CardC.class);
        if (cards.size() > 1) {
            return Optional.empty(); // 放回卡组顶的顺序影响后续抽卡，是策略
        }
        return Optional.of(new ChoiceIntent.Sort(cards.stream().map(Candidate.CardC::id).toList()));
    }


    private Optional<ChoiceIntent> declareRace(DecisionPoint point) {
        var request = (ChoiceRequest.DeclareRaceRequest) point.request();
        Set<MonsterRace> picked = new LinkedHashSet<>();
        for (MonsterRace race : request.available()) {
            if (picked.size() >= request.count()) {
                break;
            }
            picked.add(race);
        }
        return Optional.of(new ChoiceIntent.DeclareRace(picked));
    }

    private Optional<ChoiceIntent> declareAttribute(DecisionPoint point) {
        var request = (ChoiceRequest.DeclareAttributeRequest) point.request();
        Set<Attribute> picked = new LinkedHashSet<>();
        for (Attribute attr : request.available()) {
            if (picked.size() >= request.count()) {
                break;
            }
            picked.add(attr);
        }
        return Optional.of(new ChoiceIntent.DeclareAttribute(picked));
    }

    private Optional<ChoiceIntent> declareNumber(DecisionPoint point) {
        return Optional.of(new ChoiceIntent.DeclareNumber(0));
    }


    private Optional<ChoiceIntent> counter(DecisionPoint point) {
        var request = (ChoiceRequest.CounterRequest) point.request();
        Map<CandidateId, Integer> removals = new LinkedHashMap<>();
        int remaining = request.total();
        for (Candidate.CounterC c : point.candidatesOf(Candidate.CounterC.class)) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, Math.max(c.value().available(), 0));
            if (take > 0) {
                removals.put(c.id(), take);
                remaining -= take;
            }
        }
        return Optional.of(new ChoiceIntent.Counter(removals));
    }

    private static List<CandidateId> all(List<Candidate.CardC> cards) {
        return cards.stream().map(Candidate.CardC::id).toList();
    }
}
