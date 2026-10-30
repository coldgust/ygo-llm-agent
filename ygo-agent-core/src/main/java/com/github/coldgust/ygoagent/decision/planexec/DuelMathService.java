package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性数学与计数（v6 §3.4）：battleCalc / lethalScan / stateCount。
 * <p>全部纯函数：输入 DuelState + 卡牌事实，输出人类与 LLM 都可读的结算结果。
 * LLM 输出里的数字必须来自这里（引用校验强制）。
 */
public final class DuelMathService {

    /** 单次攻击的结算结果。 */
    public record AttackLine(
            long attackerInstanceId,
            long attackerPasscode,
            String attackerName,
            int attackerAtk,
            String targetName,      // null = 直接攻击
            Integer targetValue,    // 对方 ATK 或 DEF（未知为 null）
            boolean targetDefense,
            Outcome outcome,
            int damageToOpponent,
            int damageToSelf) {

        public enum Outcome {
            DESTROY_AND_DAMAGE,   // 破坏目标并造成伤害
            TRADE,                // 同归于尽（双方攻击表示）
            ATTACKER_DESTROYED,   // 攻击方被破坏
            NO_EFFECT,            // 打不动（对方守备 ≥ 我方攻击）
            DIRECT_ATTACK,
            UNRESOLVED            // 对方盖卡/身份未知
        }

        public String describe() {
            String target = targetName == null ? "直接攻击" : targetName + (targetDefense ? "(守备)" : "(攻击)");
            String base = attackerName + "(" + attackerAtk + ") → " + target;
            String tail = switch (outcome) {
                case DESTROY_AND_DAMAGE -> "破坏并造成 " + damageToOpponent + " 伤害";
                case DIRECT_ATTACK -> "直接攻击造成 " + damageToOpponent + " 伤害";
                case TRADE -> "同归于尽" + (damageToOpponent > 0 ? "，双方各受 " + damageToOpponent : "");
                case ATTACKER_DESTROYED -> "攻击方被破坏" + (damageToSelf > 0 ? "，我方受 " + damageToSelf : "");
                case NO_EFFECT -> "无法破坏（对方 " + targetValue + " ≥ 我方 " + attackerAtk + "），无伤害";
                case UNRESOLVED -> "结算未知（对方身份不可见）";
            };
            return base + "：" + tail;
        }
    }

    /** 斩杀判定。 */
    public record LethalScan(boolean directLethal, int totalAvailableDamage, int opponentLp, String summary) {
    }

    /** 关键约束计数（stateCount）。 */
    public Map<String, Object> constraints(DuelState state, CardFacts facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        int selfGySpells = 0;
        for (CardIdentity identity : state.self().graveyard()) {
            if (facts.byPasscode(identity.passcode()).map(CardFact::isMonster).map(b -> !b).orElse(false)) {
                selfGySpells++;
            }
        }
        out.put("selfLp", state.self().lifePoints());
        out.put("opponentLp", state.opponent().lifePoints());
        out.put("selfHandCount", state.self().handCount());
        out.put("selfMainMonsterZoneEmpty", state.freeMainMonsterZones(PlayerSide.SELF).size() == 5);
        out.put("selfMonsters", state.monsters(PlayerSide.SELF).size());
        out.put("selfBackrow", state.spellTraps(PlayerSide.SELF).size());
        out.put("selfGySpellCount", selfGySpells);
        out.put("opponentMonsters", state.monsters(PlayerSide.OPPONENT).size());
        out.put("opponentBackrow", state.spellTraps(PlayerSide.OPPONENT).size());
        out.put("opponentHandCount", state.opponent().handCount());
        return out;
    }

    /** 全部可攻击宣言的结算表（只算表侧攻击表示的已知怪兽）。 */
    public List<AttackLine> attackTable(DuelState state, CardFacts facts) {
        List<AttackLine> lines = new ArrayList<>();
        List<CardInstance> attackers = state.monsters(PlayerSide.SELF).stream()
                .filter(c -> c.position() == Position.FACE_UP_ATTACK)
                .toList();
        List<CardInstance> defenders = state.monsters(PlayerSide.OPPONENT);
        for (CardInstance attacker : attackers) {
            CardFact attackerFact = facts.byPasscode(attacker.passcode()).orElse(null);
            if (attackerFact == null || attackerFact.atk() == null) {
                continue;
            }
            int atk = attackerFact.atk();
            if (defenders.isEmpty()) {
                lines.add(new AttackLine(attacker.instanceId(), attacker.passcode(), attackerFact.name(),
                        atk, null, null, false, AttackLine.Outcome.DIRECT_ATTACK, atk, 0));
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
                    "盖卡/未知", null, target.position() != null && target.position().isDefense(),
                    AttackLine.Outcome.UNRESOLVED, 0, 0);
        }
        CardFact targetFact = facts.byPasscode(target.passcode()).orElse(null);
        if (targetFact == null) {
            return new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                    "未知(" + target.passcode() + ")", null, target.position().isDefense(),
                    AttackLine.Outcome.UNRESOLVED, 0, 0);
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
                            targetFact.name(), value, false, AttackLine.Outcome.DESTROY_AND_DAMAGE, diff, 0)
                    : diff == 0
                            ? new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                                    targetFact.name(), value, false, AttackLine.Outcome.TRADE, 0, 0)
                            : new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                                    targetFact.name(), value, false, AttackLine.Outcome.ATTACKER_DESTROYED, 0, -diff);
            case FACE_UP_DEFENSE -> diff > 0
                    ? new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                            targetFact.name(), value, true, AttackLine.Outcome.DESTROY_AND_DAMAGE, 0, 0)
                    : new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                            targetFact.name(), value, true, AttackLine.Outcome.NO_EFFECT, 0, 0);
            default -> new AttackLine(attacker.instanceId(), attacker.passcode(), attackerName, atk,
                    targetFact.name(), value, targetDefense, AttackLine.Outcome.UNRESOLVED, 0, 0);
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

    /**
     * 战斗数学段（锚点 [数学] 区）。
     *
     * <p>已停用【斩杀判定】：回合开始/空场时"确定伤害 0 → 不可斩杀"既无信息量又误导规划器，
     * 待斩杀模型（含战阶推进、盖卡未知、后续展开）完善后再接回。{@link #lethalScan} 仍保留为纯函数。
     */
    @Deprecated
    public String mathSection(DuelState state, CardFacts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append(constraintsSection(state, facts)).append("\n");
        List<AttackLine> lines = attackTable(state, facts);
        if (lines.isEmpty()) {
            sb.append("[数学] 攻击结算表：（无可攻击宣言或无已知攻击力）");
        } else {
            sb.append("[数学] 攻击结算表：");
            for (AttackLine line : lines) {
                sb.append("\n- ").append(line.describe());
            }
        }
        return sb.toString();
    }

    /** 约束计数段（锚点常驻）：只放确定性计数，不做任何"能不能赢"的判断。 */
    public String constraintsSection(DuelState state, CardFacts facts) {
        return "[约束] " + constraints(state, facts) + "\n";
    }

    /**
     * 攻击结算表段（仅战阶决策注入）：给出确定的攻击/伤害结算，不做斩杀结论。
     */
    public String battleTableSection(DuelState state, CardFacts facts) {
        List<AttackLine> lines = attackTable(state, facts);
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[数学] 攻击结算表：");
        for (AttackLine line : lines) {
            sb.append("\n- ").append(line.describe());
        }
        return sb.append("\n").toString();
    }

    private DuelMathService() {
    }

    /** 供静态调用的便捷实例（无状态，全部纯函数）。 */
    public static DuelMathService instance() {
        return INSTANCE;
    }

    private static final DuelMathService INSTANCE = new DuelMathService();

    /* ---------- 供锚点/事件批使用的名称解析 ---------- */

    public static String labelOf(CardInstance card, CardFacts facts) {
        if (card.isUnknown()) {
            return "未知卡";
        }
        return facts.byPasscode(card.passcode()).map(CardFact::label)
                .orElseGet(() -> "卡(" + card.passcode() + ")");
    }

    public static Optional<CardInstance> findKnown(DuelState state, long passcode, PlayerSide side) {
        return state.monsters(side).stream()
                .filter(c -> c.passcode() == passcode)
                .findFirst()
                .or(() -> state.spellTraps(side).stream().filter(c -> c.passcode() == passcode).findFirst())
                .or(() -> state.player(side).hand().stream()
                        .filter(c -> c.passcode() == passcode).findFirst());
    }

    /** ZoneKind 的可读名（锚点用）。 */
    public static String zoneLabel(ZoneKind kind) {
        return switch (kind) {
            case MAIN_MONSTER_ZONE -> "主怪区";
            case EXTRA_MONSTER_ZONE -> "额外怪区";
            case SPELL_TRAP_ZONE -> "魔陷区";
            case PENDULUM_ZONE -> "灵摆区";
            case FIELD_ZONE -> "场地区";
            case GRAVEYARD -> "墓地";
            case BANISHED -> "除外";
            case HAND -> "手牌";
            case DECK -> "卡组";
            case EXTRA_DECK -> "额外卡组";
        };
    }
}
