package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;

import com.github.coldgust.ygodomain.duel.Position;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 内置保底策略：按"最小风险"原则取候选（优先结束回合 / 不连锁 / 选最少）。
 * 全部取值来自请求给出的候选集合，构造上合法。
 */
public final class DefaultFallbackPolicy implements FallbackPolicy {

    private static final FallbackPolicy INSTANCE = new DefaultFallbackPolicy();

    public static FallbackPolicy get() {
        return INSTANCE;
    }

    private DefaultFallbackPolicy() {
    }

    @Override
    public ChoiceSelection fallback(ChoiceRequest request, int retryCount) {
        // 重试过一次以上，优先选"跳过"类动作，避免在同一个失败动作上循环
        int pass = Math.max(0, retryCount);

        if (request instanceof ChoiceRequest.MainPhaseRequest main) {
            List<ChoiceSelection.MainPhaseAction> ladder = new ArrayList<>();
            if (main.canEndTurn()) {
                ladder.add(new ChoiceSelection.EndTurn());
            }
            if (main.canEnterBattlePhase()) {
                ladder.add(new ChoiceSelection.EnterBattlePhase());
            }
            for (var card : rotate(main.normalSummonable(), pass)) {
                ladder.add(new ChoiceSelection.Summon(card));
            }
            for (var card : rotate(main.spellTrapSettable(), pass)) {
                ladder.add(new ChoiceSelection.SetSpellTrap(card));
            }
            for (var card : rotate(main.monsterSettable(), pass)) {
                ladder.add(new ChoiceSelection.SetMonster(card));
            }
            return new ChoiceSelection.MainPhaseSelection(ladder.getFirst());
        }

        if (request instanceof ChoiceRequest.BattlePhaseRequest battle) {
            if (battle.canEndBattlePhase()) {
                return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.EndBattlePhase());
            }
            if (battle.canEnterMainPhase2()) {
                return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.EnterMainPhase2());
            }
            return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.EndBattlePhase());
        }

        if (request instanceof ChoiceRequest.ChainRequest chain) {
            if (chain.hasForcedEffect()) {
                return new ChoiceSelection.ChainSelection(
                        new ChoiceSelection.ChainAnswer.Respond(
                                chain.options().stream().filter(ChoiceRequest.ChainRequest.ChainOption::forced)
                                        .findFirst().orElse(chain.options().getFirst())));
            }
            return new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE);
        }

        if (request instanceof ChoiceRequest.CardSelectRequest card) {
            int take = Math.min(card.min() + Math.min(pass, card.max() - card.min()), card.max());
            return new ChoiceSelection.CardSelection(card.candidates().subList(0, take));
        }

        if (request instanceof ChoiceRequest.TributeSelectRequest tribute) {
            int take = Math.min(tribute.min(), tribute.candidates().size());
            return new ChoiceSelection.TributeSelection(tribute.candidates().subList(0, take));
        }

        if (request instanceof ChoiceRequest.SumRequest sum) {
            var selected = new ArrayList<>(sum.mustSelect());
            int remaining = sum.targetSum();
            for (var card : sum.optional()) {
                if (selected.size() < sum.max() + sum.mustSelect().size()) {
                    selected.add(card);
                    remaining--;
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
            return new ChoiceSelection.SumSelection(selected);
        }

        if (request instanceof ChoiceRequest.UnselectRequest unselect) {
            if (pass > 0 && (unselect.finishable() || unselect.cancelable())) {
                return new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
            }
            if (!unselect.selectable().isEmpty()) {
                return new ChoiceSelection.UnselectSelection(
                        new ChoiceSelection.UnselectStep.Select(unselect.selectable().getFirst()));
            }
            return new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
        }

        if (request instanceof ChoiceRequest.PlaceRequest place) {
            int take = Math.min(place.count(), place.availableZones().size());
            return new ChoiceSelection.PlaceSelection(place.availableZones().subList(0, take));
        }

        if (request instanceof ChoiceRequest.DisfieldRequest disfield) {
            int take = Math.min(disfield.count(), disfield.availableZones().size());
            return new ChoiceSelection.PlaceSelection(disfield.availableZones().subList(0, take));
        }

        if (request instanceof ChoiceRequest.PositionRequest position) {
            // 优先表侧守备（风险最小），其次候选第一个
            if (position.positions().contains(Position.FACE_UP_DEFENSE)) {
                return new ChoiceSelection.PositionSelection(Position.FACE_UP_DEFENSE);
            }
            return new ChoiceSelection.PositionSelection(position.positions().iterator().next());
        }

        if (request instanceof ChoiceRequest.EffectYesNoRequest) {
            return new ChoiceSelection.EffectYesNoSelection(false);
        }

        if (request instanceof ChoiceRequest.YesNoRequest) {
            return new ChoiceSelection.YesNoSelection(false);
        }

        if (request instanceof ChoiceRequest.OptionRequest option) {
            return new ChoiceSelection.OptionSelection(0);
        }

        if (request instanceof ChoiceRequest.DeclareRaceRequest race) {
            var picked = race.available().stream().limit(race.count()).collect(Collectors.toSet());
            return new ChoiceSelection.DeclareRaceSelection(picked);
        }

        if (request instanceof ChoiceRequest.DeclareAttributeRequest attribute) {
            var picked = attribute.available().stream().limit(attribute.count())
                    .collect(Collectors.toSet());
            return new ChoiceSelection.DeclareAttributeSelection(picked);
        }

        if (request instanceof ChoiceRequest.DeclareCardRequest) {
            // 常见条件是"非额外卡组怪兽/指定类型"；轮换几个主卡组卡号，避免固定 0 导致 RETRY 死循环
            long[] safeCodes = {89631139L, 14558127L, 83764718L, 55144522L, 53129443L};
            return new ChoiceSelection.DeclareCardSelection(safeCodes[Math.floorMod(pass, safeCodes.length)]);
        }

        if (request instanceof ChoiceRequest.DeclareNumberRequest number) {
            return new ChoiceSelection.DeclareNumberSelection(pass % Math.max(1, number.numbers().size()));
        }

        if (request instanceof ChoiceRequest.SortRequest sort) {
            return new ChoiceSelection.SortSelection(sort.cards());
        }

        if (request instanceof ChoiceRequest.CounterRequest counter) {
            var removals = new LinkedHashMap<ChoiceRequest.CardChoice, Integer>();
            int remaining = counter.total();
            for (var candidate : counter.candidates()) {
                int take = Math.min(remaining, candidate.available());
                if (take > 0) {
                    removals.put(candidate.card(), take);
                    remaining -= take;
                }
                if (remaining == 0) {
                    break;
                }
            }
            return new ChoiceSelection.CounterSelection(removals);
        }

        throw new IllegalArgumentException("无保底策略的请求类型: " + request.getClass().getSimpleName());
    }

    /** 候选轮转：pass 次重答时跳过前 pass 个，避免重复同一失败选项。 */
    private static <T> List<T> rotate(List<T> list, int pass) {
        if (list.isEmpty() || pass == 0) {
            return list;
        }
        int offset = pass % list.size();
        var out = new ArrayList<T>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(list.get((i + offset) % list.size()));
        }
        return out;
    }
}
