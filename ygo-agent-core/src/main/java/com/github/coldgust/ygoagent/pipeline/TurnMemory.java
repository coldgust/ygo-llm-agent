package com.github.coldgust.ygoagent.pipeline;

import com.github.coldgust.ygoagent.model.Candidate;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.model.DuelDecision;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.support.FoldedLines;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合内跨级状态：五级流水各级共用的"这一手在干嘛"记忆。
 * <ul>
 *   <li>位置线索（⓪ 写入 / 兑现消费，带回合号防跨回合串格）；</li>
 *   <li>正在结算的效果（④ 给从属决策点补语义）；</li>
 *   <li>本回合已做动作 + 上一手判断（④ 单轮调用没有对话历史，靠它补）；</li>
 *   <li>本回合是否已为等计划花过一次等待（编排用）。</li>
 * </ul>
 * 只由决策线程读写（除 turnsWaitedForPlan 外），不承担任何 IO 与日志。
 * <p>模块内部协作类型（宿主不应直接使用）。
 */
public final class TurnMemory {

    /** 正在结算的效果：卡号 + 本次发动的那一条描述码，只在本回合内有效。 */
    public record EffectScope(long card, long description) {
    }

    /** 本回合动作记录上限：再多也只是噪声。 */
    private static final int TURN_ACTION_LIMIT = 8;

    private volatile EffectScope pendingEffect;
    private volatile int pendingEffectTurn = -1;

    private final List<String> turnActions = new ArrayList<>();
    private volatile int turnActionsTurn = -1;
    private volatile String lastIntent = "";

    /** 位置线索：带回合号——跨回合的旧线索必须作废，否则会把上回合想放的格子套到这回合的召唤上。 */
    private record ZoneHint(int turn, ZoneRef zone) {
    }

    private volatile ZoneHint pendingZone;
    /** 仅由决策线程（②③接地 / ④采纳草案）写入，供同一次 decide() 采集"这次选的动作要放哪"。 */
    private volatile ZoneRef lastDecisionZone;

    private final Set<Integer> turnsWaitedForPlan = ConcurrentHashMap.newKeySet();

    /** 记下"这一手发动的是哪张卡的哪一条效果"，供随后的从属决策点（选卡/选位置/选表示形式）使用。 */
    public void rememberEffectScope(DecisionPoint point, DuelDecision decision) {
        Object intent = decision.intent();
        var effectId = switch (intent) {
            case ChoiceIntent.ChainRespond r -> r.option();
            case ChoiceIntent.Activate a -> a.effect();
            default -> null;
        };
        if (effectId == null
                || (point.kind() != RequestKind.CHAIN
                && point.kind() != RequestKind.MAIN_PHASE
                && point.kind() != RequestKind.BATTLE_PHASE
                && point.kind() != RequestKind.EFFECT_YES_NO)) {
            return;
        }
        var id = effectId;
        point.candidatesOf(Candidate.EffectC.class).stream()
                .filter(c -> c.id().value().equals(id.value()))
                .findFirst()
                .ifPresent(c -> {
                    pendingEffect = new EffectScope(
                            c.value().card().identity().passcode(), c.value().description());
                    pendingEffectTurn = point.turn();
                });
    }

    /** ④ 用：本决策点若是某次发动的从属选择，给出那次效果的语义描述材料。 */
    public Optional<EffectScope> effectScopeFor(int turn) {
        return pendingEffectTurn == turn ? Optional.ofNullable(pendingEffect) : Optional.empty();
    }

    /**
     * 记录/消费位置线索（决策收尾时调用）。
     * <ul>
     *   <li>本次请求是 PLACE → 线索已消费，清空；</li>
     *   <li>本次选了会落场的动作且接地时给出了 zone → 记下（带回合号），返回给调用方发事件；</li>
     *   <li>其他情况保持原状（召唤之后可能先问表示形式、再问位置）。</li>
     * </ul>
     * 没给 zone 时不保留旧值：宁可退到"第一个可放置"，也不要把上一张卡的格子套到这张卡上。
     *
     * @return 本次记下的位置线索（供 POSITION_HINT 事件），没有则为空
     */
    public Optional<ZoneRef> rememberPlacement(DecisionPoint point, DuelDecision decision) {
        if (point.kind() == RequestKind.PLACE) {
            pendingZone = null;
            return Optional.empty();
        }
        if (!placesOnField(decision.intent())) {
            return Optional.empty();
        }
        ZoneRef zone = lastDecisionZone;
        lastDecisionZone = null;
        pendingZone = zone == null ? null : new ZoneHint(point.turn(), zone);
        return Optional.ofNullable(pendingZone == null ? null : pendingZone.zone());
    }

    private static boolean placesOnField(ChoiceIntent intent) {
        return intent instanceof ChoiceIntent.Summon
                || intent instanceof ChoiceIntent.SpecialSummon
                || intent instanceof ChoiceIntent.SetMonster
                || intent instanceof ChoiceIntent.SetSpellTrap
                || intent instanceof ChoiceIntent.Activate;
    }

    /** ⓪ 用：消费一次位置线索（一次线索只兑现一次；回合不符视为没给）。 */
    public Optional<ZoneRef> consumeZoneHint(int turn) {
        ZoneHint hint = pendingZone;
        pendingZone = null;
        if (hint != null && hint.turn() == turn) {
            return Optional.of(hint.zone());
        }
        return Optional.empty();
    }

    /** ②③④ 用：把"这次选的动作要放哪"交给下一次 rememberPlacement 采集。 */
    public void recordDecisionZone(ZoneRef zone) {
        lastDecisionZone = zone;
    }

    /**
     * 记一笔"我刚做过什么、刚才是怎么想的"。
     *
     * @param intentPreview 上一手判断的摘要（调用方已截断）
     * @param intentLine    本手动作的人话描述（IntentSummary 产出；空 = 不记动作行）
     */
    public void recordTurnAction(int turn, String intentPreview, String intentLine) {
        if (turn != turnActionsTurn) {
            turnActions.clear();
            turnActionsTurn = turn;
            lastIntent = "";
        }
        lastIntent = intentPreview;
        if (intentLine == null || intentLine.isBlank()) {
            return;
        }
        FoldedLines.add(turnActions, intentLine);
        while (turnActions.size() > TURN_ACTION_LIMIT) {
            turnActions.remove(0);
        }
    }

    /** ④ 用：本回合已做动作（有界）+ 上一手怎么想的。 */
    public List<String> turnActions() {
        return List.copyOf(turnActions);
    }

    public String lastIntent() {
        return lastIntent;
    }

    /** 编排用：本回合是否已经为等计划花过那次等待。 */
    public boolean waitedForPlan(int turn) {
        return turnsWaitedForPlan.contains(turn);
    }

    public void markWaitedForPlan(int turn) {
        turnsWaitedForPlan.add(turn);
    }
}
