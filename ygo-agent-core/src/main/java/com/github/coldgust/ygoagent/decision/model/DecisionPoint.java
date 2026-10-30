package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;

import java.util.List;
import java.util.Optional;

/**
 * 一个决策点：请求 + 可见局面 + 编号后的候选。
 *
 * @param seq          请求在会话内的序号（单调递增，仅用于追踪）
 * @param turn         回合数
 * @param phase        阶段
 * @param side         观察者视角（SELF = 我方）
 * @param request      原始请求（候选引用的唯一真值来源）
 * @param state        请求到达时的战场镜像
 * @param candidates   编号后的候选（payload 持有原始对象引用）
 * @param stateHash    可见局面指纹（SHA-256，规范化字符串）
 * @param requestHash  请求指纹（SHA-256）
 * @param kind         请求类型
 */
public record DecisionPoint(
        long seq,
        int turn,
        Phase phase,
        PlayerSide side,
        ChoiceRequest request,
        DuelState state,
        List<Candidate> candidates,
        String stateHash,
        String requestHash,
        RequestKind kind
) {

    public DecisionPoint {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public Optional<Candidate> candidate(CandidateId id) {
        return candidates.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public boolean candidateIsPresent(CandidateId id) {
        return candidate(id).isPresent();
    }

    public <T extends Candidate> List<T> candidatesOf(Class<T> type) {
        return candidates.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
