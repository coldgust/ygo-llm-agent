package com.github.coldgust.ygoagent.decision.map;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.port.SelectionMapper;
import com.github.coldgust.ygoagent.decision.quirks.RequestQuirks;

import com.github.coldgust.ygodomain.zone.ZoneRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图 → ChoiceSelection：按引用回指请求候选对象（不做 equals/下标查找）。
 */
public final class SelectionMapperImpl implements SelectionMapper {

    @Override
    public ChoiceSelection map(DecisionPoint point, ChoiceIntent intent) {
        ChoiceRequest request = point.request();
        return switch (intent) {
            case ChoiceIntent.Summon i -> {
                require(request instanceof ChoiceRequest.MainPhaseRequest, "Summon 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.Summon(card(point, i.card())));
            }
            case ChoiceIntent.SpecialSummon i -> {
                require(request instanceof ChoiceRequest.MainPhaseRequest, "SpecialSummon 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.SpecialSummon(card(point, i.card())));
            }
            case ChoiceIntent.Reposition i -> {
                require(request instanceof ChoiceRequest.MainPhaseRequest, "Reposition 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.Reposition(card(point, i.card())));
            }
            case ChoiceIntent.SetMonster i -> {
                require(request instanceof ChoiceRequest.MainPhaseRequest, "SetMonster 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.SetMonster(card(point, i.card())));
            }
            case ChoiceIntent.SetSpellTrap i -> {
                require(request instanceof ChoiceRequest.MainPhaseRequest, "SetSpellTrap 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.SetSpellTrap(card(point, i.card())));
            }
            case ChoiceIntent.Activate i -> {
                if (request instanceof ChoiceRequest.ChainRequest) {
                    yield new ChoiceSelection.ChainSelection(
                            new ChoiceSelection.ChainAnswer.Respond(chainOption(point, i.effect())));
                }
                require(request instanceof ChoiceRequest.MainPhaseRequest, "Activate 需要主阶段请求");
                yield new ChoiceSelection.MainPhaseSelection(
                        new ChoiceSelection.Activate(effect(point, i.effect())));
            }
            case ChoiceIntent.EnterBattlePhase ignored -> {
                var m = mainPhase(request, "EnterBattlePhase");
                require(m.canEnterBattlePhase(), "不允许进入战斗阶段");
                yield new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EnterBattlePhase.INSTANCE);
            }
            case ChoiceIntent.EndTurn ignored -> {
                var m = mainPhase(request, "EndTurn");
                require(m.canEndTurn(), "不允许结束回合");
                yield new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE);
            }
            case ChoiceIntent.ShuffleHand ignored -> {
                var m = mainPhase(request, "ShuffleHand");
                require(m.canShuffleHand(), "不允许洗牌");
                yield new ChoiceSelection.MainPhaseSelection(ChoiceSelection.ShuffleHand.INSTANCE);
            }
            case ChoiceIntent.BattleActivate i -> {
                require(request instanceof ChoiceRequest.BattlePhaseRequest, "BattleActivate 需要战斗阶段请求");
                yield new ChoiceSelection.BattlePhaseSelection(
                        new ChoiceSelection.BattleActivate(effect(point, i.effect())));
            }
            case ChoiceIntent.Attack i -> {
                require(request instanceof ChoiceRequest.BattlePhaseRequest, "Attack 需要战斗阶段请求");
                yield new ChoiceSelection.BattlePhaseSelection(
                        new ChoiceSelection.Attack(attack(point, i.attack())));
            }
            case ChoiceIntent.EnterMainPhase2 ignored -> {
                var b = battlePhase(request, "EnterMainPhase2");
                require(b.canEnterMainPhase2(), "不允许进入主阶段2");
                yield new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EnterMainPhase2.INSTANCE);
            }
            case ChoiceIntent.EndBattlePhase ignored -> {
                battlePhase(request, "EndBattlePhase");
                yield new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EndBattlePhase.INSTANCE);
            }
            case ChoiceIntent.ChainDecline ignored -> {
                var c = chain(request, "ChainDecline");
                require(!c.hasForcedEffect(), "存在强制效果，不能不连锁");
                yield new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE);
            }
            case ChoiceIntent.ChainRespond i -> {
                var c = chain(request, "ChainRespond");
                var option = chainOption(point, i.option());
                require(c.options().contains(option), "连锁选项不在请求中");
                yield new ChoiceSelection.ChainSelection(new ChoiceSelection.ChainAnswer.Respond(option));
            }
            case ChoiceIntent.EffectYesNo i -> new ChoiceSelection.EffectYesNoSelection(i.activate());
            case ChoiceIntent.YesNo i -> new ChoiceSelection.YesNoSelection(i.yes());
            case ChoiceIntent.Option i -> {
                var o = as(request, ChoiceRequest.OptionRequest.class, "Option");
                require(i.index() >= 0
                        && (o.optionDescriptions().isEmpty() || i.index() < o.optionDescriptions().size()),
                        "选项下标越界");
                yield new ChoiceSelection.OptionSelection(i.index());
            }
            case ChoiceIntent.Select i -> {
                var s = as(request, ChoiceRequest.CardSelectRequest.class, "Select");
                requireInRange(i.cards().size(), s.min(), s.max());
                yield new ChoiceSelection.CardSelection(cards(point, i.cards()));
            }
            case ChoiceIntent.Tribute i -> {
                var t = as(request, ChoiceRequest.TributeSelectRequest.class, "Tribute");
                requireInRange(i.cards().size(), t.min(), t.max());
                yield new ChoiceSelection.TributeSelection(tributes(point, i.cards()));
            }
            case ChoiceIntent.Sum i -> {
                as(request, ChoiceRequest.SumRequest.class, "Sum");
                yield new ChoiceSelection.SumSelection(cards(point, i.cards()));
            }
            case ChoiceIntent.UnselectSelect i ->
                    new ChoiceSelection.UnselectSelection(
                            new ChoiceSelection.UnselectStep.Select(card(point, i.card())));
            case ChoiceIntent.UnselectUndo i -> {
                var u = as(request, ChoiceRequest.UnselectRequest.class, "UnselectUndo");
                var choice = card(point, i.card());
                require(u.selected().stream().anyMatch(c -> c == choice), "未选中的卡不能反悔");
                yield new ChoiceSelection.UnselectSelection(
                        new ChoiceSelection.UnselectStep.Unselect(choice));
            }
            case ChoiceIntent.UnselectFinish ignored -> {
                var u = as(request, ChoiceRequest.UnselectRequest.class, "UnselectFinish");
                require(u.finishable() || u.cancelable(), "当前不能结束选择");
                yield new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
            }
            case ChoiceIntent.Cancel ignored -> {
                require(request.cancelable(), "当前请求不允许取消");
                if (request instanceof ChoiceRequest.UnselectRequest u && u.min() > 0) {
                    throw new IllegalArgumentException(
                            "至少要选 " + u.min() + " 张，不能取消（取消会回滚上一步并重复询问）");
                }
                yield ChoiceSelection.Cancel.INSTANCE;
            }
            case ChoiceIntent.Place i -> {
                int count = request instanceof ChoiceRequest.PlaceRequest p ? p.count()
                        : as(request, ChoiceRequest.DisfieldRequest.class, "Place").count();
                int expected = RequestQuirks.placeZoneCount(count,
                        request instanceof ChoiceRequest.PlaceRequest p2 ? p2.availableZones().size()
                                : ((ChoiceRequest.DisfieldRequest) request).availableZones().size());
                require(i.zones().size() == expected,
                        "区域数量不匹配: 需要 " + expected + "（请求 count=" + count + "）");
                yield new ChoiceSelection.PlaceSelection(zones(point, i.zones()));
            }
            case ChoiceIntent.PositionChoice i -> {
                var p = as(request, ChoiceRequest.PositionRequest.class, "PositionChoice");
                require(p.positions().isEmpty() || p.positions().contains(i.position()), "表示形式不在候选中");
                yield new ChoiceSelection.PositionSelection(i.position());
            }
            case ChoiceIntent.DeclareRace i -> {
                var d = as(request, ChoiceRequest.DeclareRaceRequest.class, "DeclareRace");
                require(i.races().size() == d.count(), "宣言种族数量不匹配");
                require(d.available().containsAll(i.races()), "宣言种族不在候选中");
                yield new ChoiceSelection.DeclareRaceSelection(i.races());
            }
            case ChoiceIntent.DeclareAttribute i -> {
                var d = as(request, ChoiceRequest.DeclareAttributeRequest.class, "DeclareAttribute");
                require(i.attributes().size() == d.count(), "宣言属性数量不匹配");
                require(d.available().containsAll(i.attributes()), "宣言属性不在候选中");
                yield new ChoiceSelection.DeclareAttributeSelection(i.attributes());
            }
            case ChoiceIntent.DeclareCard i -> {
                as(request, ChoiceRequest.DeclareCardRequest.class, "DeclareCard");
                require(i.passcode() > 0, "宣言卡号必须为正");
                yield new ChoiceSelection.DeclareCardSelection(i.passcode());
            }
            case ChoiceIntent.DeclareNumber i -> {
                var d = as(request, ChoiceRequest.DeclareNumberRequest.class, "DeclareNumber");
                require(i.index() >= 0 && (d.numbers().isEmpty() || i.index() < d.numbers().size()),
                        "宣言数字下标越界");
                yield new ChoiceSelection.DeclareNumberSelection(i.index());
            }
            case ChoiceIntent.Sort i -> {
                var s = as(request, ChoiceRequest.SortRequest.class, "Sort");
                List<ChoiceRequest.CardChoice> ordered = cards(point, i.order());
                require(ordered.size() == s.cards().size(), "排序必须给出完整排列");
                yield new ChoiceSelection.SortSelection(ordered);
            }
            case ChoiceIntent.Counter i -> {
                var c = as(request, ChoiceRequest.CounterRequest.class, "Counter");
                Map<ChoiceRequest.CardChoice, Integer> removals = new LinkedHashMap<>();
                int total = 0;
                for (var entry : i.removals().entrySet()) {
                    var choice = counterCandidate(point, entry.getKey());
                    require(entry.getValue() >= 0 && entry.getValue() <= choice.available(),
                            "指示物数量越界");
                    removals.put(choice.card(), entry.getValue());
                    total += entry.getValue();
                }
                require(total == c.total(), "指示物总数不匹配: " + total + " != " + c.total());
                yield new ChoiceSelection.CounterSelection(removals);
            }
        };
    }


    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new SelectionException(message);
        }
    }

    private static void requireInRange(int n, int min, int max) {
        require(n >= min && n <= max, "选择数量越界: " + n + " 不在 [" + min + ", " + max + "]");
    }

    private static <T extends ChoiceRequest> T as(ChoiceRequest request, Class<T> type, String intent) {
        require(type.isInstance(request), intent + " 需要 " + type.getSimpleName());
        return type.cast(request);
    }

    private static ChoiceRequest.MainPhaseRequest mainPhase(ChoiceRequest request, String intent) {
        return as(request, ChoiceRequest.MainPhaseRequest.class, intent);
    }

    private static ChoiceRequest.BattlePhaseRequest battlePhase(ChoiceRequest request, String intent) {
        return as(request, ChoiceRequest.BattlePhaseRequest.class, intent);
    }

    private static ChoiceRequest.ChainRequest chain(ChoiceRequest request, String intent) {
        return as(request, ChoiceRequest.ChainRequest.class, intent);
    }

    private static Candidate candidate(DecisionPoint point, CandidateId id) {
        return point.candidate(id).orElseThrow(() -> new SelectionException("候选不存在: " + id));
    }

    private static ChoiceRequest.CardChoice card(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.CardC, "候选类型不是卡: " + id);
        return ((Candidate.CardC) c).value();
    }

    private static ChoiceRequest.EffectChoice effect(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.EffectC, "候选类型不是效果: " + id);
        return ((Candidate.EffectC) c).value();
    }

    private static ChoiceRequest.AttackChoice attack(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.AttackC, "候选类型不是攻击: " + id);
        return ((Candidate.AttackC) c).value();
    }

    private static ChoiceRequest.TributeChoice tributeCandidate(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.TributeC, "候选类型不是祭品: " + id);
        return ((Candidate.TributeC) c).value();
    }

    private static ChoiceRequest.CounterRequest.CounterChoice counterCandidate(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.CounterC, "候选类型不是指示物: " + id);
        return ((Candidate.CounterC) c).value();
    }

    private static ZoneRef zone(DecisionPoint point, CandidateId id) {
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.ZoneC, "候选类型不是区域: " + id);
        return ((Candidate.ZoneC) c).value();
    }

    private static ChoiceRequest.ChainRequest.ChainOption chainOption(DecisionPoint point, CandidateId id) {
        var request = (ChoiceRequest.ChainRequest) point.request();
        Candidate c = candidate(point, id);
        require(c instanceof Candidate.EffectC, "连锁候选类型不是效果: " + id);
        var effect = ((Candidate.EffectC) c).value();
        for (var option : request.options()) {
            if (option.effect() == effect) {
                return option;
            }
        }
        throw new SelectionException("连锁选项不存在: " + id);
    }

    private static List<ChoiceRequest.CardChoice> cards(DecisionPoint point, List<CandidateId> ids) {
        List<ChoiceRequest.CardChoice> out = new ArrayList<>(ids.size());
        for (CandidateId id : ids) {
            out.add(card(point, id));
        }
        return List.copyOf(out);
    }

    private static List<ChoiceRequest.TributeChoice> tributes(DecisionPoint point, List<CandidateId> ids) {
        List<ChoiceRequest.TributeChoice> out = new ArrayList<>(ids.size());
        for (CandidateId id : ids) {
            out.add(tributeCandidate(point, id));
        }
        return List.copyOf(out);
    }

    private static List<ZoneRef> zones(DecisionPoint point, List<CandidateId> ids) {
        List<ZoneRef> out = new ArrayList<>(ids.size());
        for (CandidateId id : ids) {
            out.add(zone(point, id));
        }
        return List.copyOf(out);
    }
}
