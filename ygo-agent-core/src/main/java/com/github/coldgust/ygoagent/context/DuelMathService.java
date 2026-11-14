package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import java.util.ArrayList;
import java.util.List;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;

/**
 * 确定性战斗数学（v6 §3.4）：attackTable / lethalScan。
 * <p>全部纯函数：输入 DuelState + 卡牌事实，输出人类与 LLM 都可读的结算结果。
 * 消费者是 {@code llm.MathTool}（按需注册给 LLM 的工具）；局面计数归 BoardSections
 * 的战场行渲染，本类不再承担任何 prompt 注入职责。
 */
public final class DuelMathService {

    public record AttackLine(
            long attackerInstanceId,
            long attackerPasscode,
            String attackerName,
            int attackerAtk,
            String targetName,      // null = 直接攻击
            Long targetInstanceId,  // null = 直接攻击
            Integer targetValue,    // 对方 ATK 或 DEF（未知为 null）
            boolean targetDefense,
            Outcome outcome,
            int damageToOpponent,
            int damageToSelf) {

        public enum Outcome {
            DESTROY_AND_DAMAGE,
            TRADE,
            ATTACKER_DESTROYED,
            NO_EFFECT,
            DIRECT_ATTACK,
            UNRESOLVED
        }

        public String describe() {
            String target = targetName == null ? "直接攻击" : targetName + (targetDefense ? "(守备)" : "(攻击)");
            String base = attackerName + "(" + attackerAtk + ") → " + target;
            // 视角中性：威胁表（对方视角）也复用同一文案，不写死"我方/对方"
            String tail = switch (outcome) {
                case DESTROY_AND_DAMAGE -> "破坏并造成 " + damageToOpponent + " 伤害";
                case DIRECT_ATTACK -> "直接攻击造成 " + damageToOpponent + " 伤害";
                case TRADE -> "同归于尽" + (damageToOpponent > 0 ? "，双方各受 " + damageToOpponent : "");
                case ATTACKER_DESTROYED -> "攻击方被破坏" + (damageToSelf > 0 ? "，攻击方受 " + damageToSelf : "");
                case NO_EFFECT -> "无法破坏（目标 " + targetValue + " ≥ 攻击方 " + attackerAtk + "），无伤害";
                case UNRESOLVED -> "结算未知（目标身份不可见）";
            };
            return base + "：" + tail;
        }
    }

    public record LethalScan(boolean directLethal, int totalAvailableDamage, int opponentLp, String summary) {
    }

    public List<AttackLine> attackTable(DuelState state, CardFacts facts) {
        return attackTable(state, facts, PlayerSide.SELF);
    }

    /** 双方通用攻击结算表：attackerSide 的表侧攻击怪兽 × 对面全场（威胁评估传 OPPONENT）。 */
    public List<AttackLine> attackTable(DuelState state, CardFacts facts, PlayerSide attackerSide) {
        List<AttackLine> lines = new ArrayList<>();
        List<CardInstance> attackers = state.monsters(attackerSide).stream()
                .filter(c -> c.position() == Position.FACE_UP_ATTACK)
                .toList();
        List<CardInstance> defenders = state.monsters(attackerSide == PlayerSide.SELF
                ? PlayerSide.OPPONENT : PlayerSide.SELF);
        for (CardInstance attacker : attackers) {
            CardFact attackerFact = facts.byPasscode(attacker.passcode()).orElse(null);
            if (attackerFact == null || attackerFact.atk() == null) {
                continue;
            }
            int atk = attackerFact.atk();
            if (defenders.isEmpty()) {
                lines.add(new AttackLine(attacker.instanceId(), attacker.passcode(), attackerFact.name(),
                        atk, null, null, null, false, AttackLine.Outcome.DIRECT_ATTACK, atk, 0));
                continue;
            }
            for (CardInstance target : defenders) {
                lines.add(resolveAttack(attacker, attackerFact, atk, target, facts));
            }
        }
        return lines;
    }

    private AttackLine resolveAttack(CardInstance attacker, CardFact attackerFact, int atk,
                                     CardInstance target, CardFacts facts) {
        String attackerName = attackerFact.name();
        if (target.isUnknown() || target.position() == null || !target.position().isFaceUp()) {
            return new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                    "盖卡/未知", target.instanceId(), null,
                    target.position() != null && target.position().isDefense(),
                    AttackLine.Outcome.UNRESOLVED, 0, 0);
        }
        CardFact targetFact = facts.byPasscode(target.passcode()).orElse(null);
        if (targetFact == null) {
            return new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                    "未知(" + target.passcode() + ")", target.instanceId(), null,
                    target.position().isDefense(), AttackLine.Outcome.UNRESOLVED, 0, 0);
        }
        boolean targetDefense = target.position().isDefense();
        Integer value = targetDefense ? targetFact.defense() : targetFact.atk();
        if (value == null) {
            value = 0;
        }
        int diff = atk - value;
        return switch (target.position()) {
            case FACE_UP_ATTACK -> diff > 0
                    ? new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                            targetFact.name(), target.instanceId(), value, false,
                            AttackLine.Outcome.DESTROY_AND_DAMAGE, diff, 0)
                    : diff == 0
                            ? new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                                    targetFact.name(), target.instanceId(), value, false,
                                    AttackLine.Outcome.TRADE, 0, 0)
                            : new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                                    targetFact.name(), target.instanceId(), value, false,
                                    AttackLine.Outcome.ATTACKER_DESTROYED, 0, -diff);
            case FACE_UP_DEFENSE -> diff > 0
                    ? new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                            targetFact.name(), target.instanceId(), value, true,
                            AttackLine.Outcome.DESTROY_AND_DAMAGE, 0, 0)
                    : new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                            targetFact.name(), target.instanceId(), value, true,
                            AttackLine.Outcome.NO_EFFECT, 0, 0);
            default -> new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                    targetFact.name(), target.instanceId(), value, targetDefense,
                    AttackLine.Outcome.UNRESOLVED, 0, 0);
        };
    }

    /** 斩杀判定（保守口径：仅统计确定可造成伤害的攻击）。 */
    public LethalScan lethalScan(DuelState state, CardFacts facts) {
        int opponentLp = state.opponent().lifePoints();
        List<AttackLine> lines = attackTable(state, facts);
        int certain = 0;
        boolean hasUnknown = false;
        for (AttackLine line : lines) {
            switch (line.outcome()) {
                case DIRECT_ATTACK, DESTROY_AND_DAMAGE -> certain += line.damageToOpponent();
                case TRADE -> { /* 同归于尽通常无伤害计入（对方攻击表示受互伤，保守不计） */ }
                case UNRESOLVED -> hasUnknown = true;
                default -> { }
            }
        }
        boolean directLethal = certain >= opponentLp && opponentLp > 0 && !lines.isEmpty();
        String summary = "确定伤害合计 " + certain + " / 对方 LP " + opponentLp
                + (hasUnknown ? "（含未解明的盖卡目标，实际可能更高）" : "");
        return new LethalScan(directLethal, certain, opponentLp, summary);
    }

    public DuelMathService() {
    }
}
