package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 把请求里的候选按固定分组顺序编号。
 * <p>分组顺序即枚举顺序：主阶段六组（召唤/特招/改形/盖怪/盖魔陷/发动）→ 战阶两组（发动/攻击）
 * → 其余单组。id 在请求内稳定，用于 RETRY 幂等。
 */
public final class Candidates {

    public static List<Candidate> from(ChoiceRequest request) {
        List<Candidate> out = new ArrayList<>();
        // 从 C1 起连续编号。弃权类动作（不响应/不发动/取消）现在也是真候选（DeclineC），
        // 不再有"C0 伪候选"——那样 choice 可能落在候选集之外，判越界后整条决策会降级。
        int[] seq = {1};

        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            m.normalSummonable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            m.specialSummonable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            m.repositionable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            m.monsterSettable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            m.spellTrapSettable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            m.activatable().forEach(c -> out.add(new Candidate.EffectC(next(seq), c)));
            // 阶段级动作也要有编号：否则模型只能用 C0 表达"结束回合"，而 C0 在主阶段并不存在
            if (m.canEnterBattlePhase()) {
                out.add(new Candidate.FlagC(next(seq), Candidate.Flag.ENTER_BATTLE));
            }
            // 「结束回合」只看 canEndTurn：先手第 1 回合不能进战阶（canEnterBattlePhase=false），
            // 曾经把两者绑在一起 → 第一回合整个"结束回合"候选消失，模型想空过却没有编号可用，
            // 只能填唯一的 C1（通召灰流丽），自述写着"改选弃权空过"、票却投给了通召。
            if (m.canEndTurn()) {
                out.add(new Candidate.FlagC(next(seq), Candidate.Flag.END_TURN));
            }
            if (m.canShuffleHand()) {
                out.add(new Candidate.FlagC(next(seq), Candidate.Flag.SHUFFLE_HAND));
            }
        } else if (request instanceof ChoiceRequest.BattlePhaseRequest b) {
            b.activatable().forEach(c -> out.add(new Candidate.EffectC(next(seq), c)));
            b.attackable().forEach(c -> out.add(new Candidate.AttackC(next(seq), c)));
            if (b.canEnterMainPhase2()) {
                out.add(new Candidate.FlagC(next(seq), Candidate.Flag.ENTER_MAIN2));
            }
            if (b.canEndBattlePhase()) {
                out.add(new Candidate.FlagC(next(seq), Candidate.Flag.END_BATTLE));
            }
        } else if (request instanceof ChoiceRequest.CardSelectRequest s) {
            s.candidates().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            if (s.cancelable()) {
                out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.CANCEL));
            }
        } else if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            t.candidates().forEach(c -> out.add(new Candidate.TributeC(next(seq), c)));
        } else if (request instanceof ChoiceRequest.SumRequest s) {
            s.mustSelect().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            s.optional().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
        } else if (request instanceof ChoiceRequest.UnselectRequest u) {
            u.selectable().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            u.selected().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
            if (u.finishable()) {
                out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.UNSELECT_FINISH));
            } else if (u.cancelable()) {
                out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.CANCEL));
            }
        } else if (request instanceof ChoiceRequest.ChainRequest c) {
            c.options().forEach(o -> out.add(new Candidate.EffectC(next(seq), o.effect())));
            out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.CHAIN_DECLINE));
        } else if (request instanceof ChoiceRequest.EffectYesNoRequest y) {
            // "是否发动"也要有一个"发动"候选：否则执行器看到空候选就直接走兜底，
            // 等于又把判断交给规则（本局实测 1 次）。
            out.add(new Candidate.EffectC(next(seq),
                    new ChoiceRequest.EffectChoice(y.card(), y.description())));
            out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.EFFECT_NO));
        } else if (request instanceof ChoiceRequest.YesNoRequest) {
            out.add(new Candidate.DeclineC(next(seq), Candidate.Decline.YES_NO_NO));
        } else if (request instanceof ChoiceRequest.PlaceRequest p) {
            p.availableZones().forEach(z -> out.add(new Candidate.ZoneC(next(seq), z)));
        } else if (request instanceof ChoiceRequest.DisfieldRequest d) {
            d.availableZones().forEach(z -> out.add(new Candidate.ZoneC(next(seq), z)));
        } else if (request instanceof ChoiceRequest.SortRequest s) {
            s.cards().forEach(c -> out.add(new Candidate.CardC(next(seq), c)));
        } else if (request instanceof ChoiceRequest.CounterRequest c) {
            c.candidates().forEach(x -> out.add(new Candidate.CounterC(next(seq), x)));
        }
        return List.copyOf(out);
    }

    private static CandidateId next(int[] seq) {
        return CandidateId.of(seq[0]++);
    }

    private Candidates() {
    }
}
