package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.support.FoldedLines;
import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 决斗记录段的渲染：把 {@link DuelTimeline} 的条目流翻成给模型看的过程线。
 *
 * <p>三条硬约定，结构上由本类保证：
 * <ul>
 *   <li>「发动」只表示<b>已发动</b>、不表示已生效——协议不逐环推送结算结果，
 *       渲染层不替模型下"已生效"结论（实机事故：把"发动了灰流丽"当成"幕帘已被无效"）。</li>
 *   <li>效果描述码必须经 {@link CardText} 解条款，绝不输出裸效果码。</li>
 *   <li>身份不可见的卡渲染"内容不可见"，绝不猜。</li>
 * </ul>
 *
 * <p>两个出口，token 预算不同：决策包每手都发，只带本回合（已结算回合由
 * {@link #recordOfClosedTurns} 作为独立前缀承载，回合内不变、可吃上下文缓存）；
 * 规划器锚点每回合只发一两次，同样用 {@link #recordOfClosedTurns}。
 * 我方提交过的决策（{@link DuelTimeline.Entry.DecisionMade}）在两种出口里都过滤：
 * 我方动作已由决策记忆与规划器对话承担，重复反而会淹没"对方做了什么"。
 */
public final class DuelRecordSections {

    /** 决策包时间线的行数上限：超限丢最旧的（最近动作优先）。 */
    private static final int TIMELINE_MAX_LINES = 30;
    /** 回合记录里逐条展开的最近回合数；更早的回合各压成一行摘要。 */
    private static final int FULL_RECORD_RECENT_TURNS = 3;
    /** 摘要行里每类最多点名的卡数。 */
    private static final int SUMMARY_NAME_LIMIT = 3;

    private final CardFacts facts;
    private final CardText cardText;

    public DuelRecordSections(CardFacts facts, CardText cardText) {
        this.facts = facts;
        this.cardText = cardText;
    }

    /**
     * 决策包用：本回合时间线。上回合的摘要与"发动≠生效"声明由回合记录前缀
     * （{@link #recordOfClosedTurns}）承载，这里只带本回合。timeline 为 null 或无内容时返回空串。
     */
    public String turnTimeline(int turn, boolean myTurn, DuelTimeline timeline) {
        if (timeline == null) {
            return "";
        }
        List<DuelTimeline.Entry> current = dropped(timeline.turn(turn));
        if (current.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[本回合时间线] T").append(turn)
                .append(myTurn ? "（我方回合）" : "（对方回合）").append("，从回合开始到现在：\n");
        List<String> lines = lines(current, false);
        int from = Math.max(0, lines.size() - TIMELINE_MAX_LINES);
        if (from > 0) {
            sb.append("  …（本回合更早的动作略）\n");
        }
        for (int i = from; i < lines.size(); i++) {
            sb.append("  - ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 回合记录前缀：**已结算回合**（turn &lt; currentTurn）的完整过程——最近
     * {@value FULL_RECORD_RECENT_TURNS} 回合逐条展开，更早的回合各一行摘要。
     *
     * <p>在第 N 回合内，这份内容永不变化：规划器锚点与决策器的每手调用共用同一渲染，
     * 以此作为跨调用一致的前缀（上下文缓存的第二个层级）。结算中的回合不在此列，
     * 由决策包的 [本回合时间线] 承载。timeline 为 null 或无已结算内容时返回空串。
     */
    public String recordOfClosedTurns(int currentTurn, DuelTimeline timeline) {
        if (timeline == null || currentTurn <= 1) {
            return "";
        }
        List<DuelTimeline.Entry> closed = dropped(timeline.since(0)).stream()
                .filter(e -> e.turn() > 0 && e.turn() < currentTurn)
                .toList();
        if (closed.isEmpty()) {
            return "";
        }
        TreeMap<Integer, List<DuelTimeline.Entry>> byTurn = new TreeMap<>();
        for (DuelTimeline.Entry entry : closed) {
            byTurn.computeIfAbsent(entry.turn(), k -> new ArrayList<>()).add(entry);
        }
        StringBuilder sb = new StringBuilder(
                "[决斗记录] 开局至今双方做过的事（旧回合只留摘要；「发动」不代表已生效，结果以 [局面] 为准）：\n");
        int recentFrom = Math.max(1, byTurn.lastKey() - FULL_RECORD_RECENT_TURNS + 1);
        for (Map.Entry<Integer, List<DuelTimeline.Entry>> group : byTurn.entrySet()) {
            int turn = group.getKey();
            if (turn < recentFrom) {
                String summary = summarizeTurn(turn, group.getValue());
                if (!summary.isEmpty()) {
                    sb.append("  ").append(summary).append('\n');
                }
                continue;
            }
            List<String> lines = lines(group.getValue(), true);
            int from = Math.max(0, lines.size() - TIMELINE_MAX_LINES);
            sb.append("  T").append(turn).append(turnHeaderSuffix(group.getValue())).append("：\n");
            if (from > 0) {
                sb.append("    …（更早的动作略）\n");
            }
            for (int i = from; i < lines.size(); i++) {
                sb.append("    - ").append(lines.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 事件批用的单条目行（过滤我方决策行、折叠连续同文行）；
     * 条目由 PlannerSession 经端口增量拉取，本类只负责渲染。
     */
    public List<String> lines(List<DuelTimeline.Entry> entries) {
        return lines(entries, true);
    }


    /** 一回合压成一行：双方发动了什么、上场/盖了几张、LP 怎么变的。 */
    private String summarizeTurn(int turn, List<DuelTimeline.Entry> entries) {
        List<String> parts = new ArrayList<>();
        List<String> selfEffects = effectNames(entries, DuelTimeline.Side.SELF);
        List<String> oppEffects = effectNames(entries, DuelTimeline.Side.OPPONENT);
        if (!selfEffects.isEmpty()) {
            parts.add("我方发动 " + clipNames(selfEffects));
        }
        if (!oppEffects.isEmpty()) {
            parts.add("对方发动 " + clipNames(oppEffects));
        }
        long selfField = count(entries, DuelTimeline.Side.SELF, monsterZones());
        long oppField = count(entries, DuelTimeline.Side.OPPONENT, monsterZones());
        if (selfField > 0) {
            parts.add("我方上场 " + selfField + " 张");
        }
        if (oppField > 0) {
            parts.add("对方上场 " + oppField + " 张");
        }
        long selfSet = count(entries, DuelTimeline.Side.SELF, Set.of(ZoneKind.SPELL_TRAP_ZONE));
        long oppSet = count(entries, DuelTimeline.Side.OPPONENT, Set.of(ZoneKind.SPELL_TRAP_ZONE));
        if (selfSet > 0) {
            parts.add("我方盖放魔陷 " + selfSet + " 张");
        }
        if (oppSet > 0) {
            parts.add("对方盖放魔陷 " + oppSet + " 张");
        }
        lpParts(entries).forEach(parts::add);
        if (parts.isEmpty()) {
            return "";
        }
        return "T" + turn + turnHeaderSuffix(entries) + "摘要：" + String.join("；", parts);
    }

    /** 每侧一条 LP 净变化（有变动才给）。 */
    private List<String> lpParts(List<DuelTimeline.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (DuelTimeline.Side side : List.of(DuelTimeline.Side.SELF, DuelTimeline.Side.OPPONENT)) {
            int net = 0;
            Integer lpAfter = null;
            for (DuelTimeline.Entry entry : entries) {
                if (entry instanceof DuelTimeline.Entry.LpChanged lp && lp.side() == side) {
                    net += lp.delta();
                    lpAfter = lp.lpAfter();
                }
            }
            if (net != 0) {
                out.add(sideText(side) + " LP " + (net > 0 ? "+" : "") + net
                        + (lpAfter == null ? "" : "（→" + lpAfter + "）"));
            }
        }
        return out;
    }

    private List<String> effectNames(List<DuelTimeline.Entry> entries, DuelTimeline.Side side) {
        Set<String> names = new LinkedHashSet<>();
        for (DuelTimeline.Entry entry : entries) {
            if (entry instanceof DuelTimeline.Entry.EffectActivated e && e.side() == side) {
                names.add(cardLabel(e.passcode()));
            }
        }
        return List.copyOf(names);
    }

    private long count(List<DuelTimeline.Entry> entries, DuelTimeline.Side side, Set<ZoneKind> kinds) {
        return entries.stream()
                .filter(e -> e instanceof DuelTimeline.Entry.CardMoved m
                        && m.side() == side && m.toZone() != null && kinds.contains(m.toZone().kind()))
                .count();
    }

    private static Set<ZoneKind> monsterZones() {
        return Set.of(ZoneKind.MAIN_MONSTER_ZONE, ZoneKind.EXTRA_MONSTER_ZONE);
    }


    private List<String> lines(List<DuelTimeline.Entry> entries, boolean withPhases) {
        List<String> out = new ArrayList<>();
        for (DuelTimeline.Entry entry : entries) {
            String line = line(entry, withPhases);
            if (line == null) {
                continue;
            }
            // 连续同文行折叠 ×N：对手一次抽 3 张是 3 条同文事件，逐条只会刷屏
            FoldedLines.add(out, line);
        }
        return out;
    }

    /** 单条目 → 一行；null 表示该条目不出现在行里（回合头/我方决策由别处承担）。 */
    private String line(DuelTimeline.Entry entry, boolean withPhases) {
        if (entry instanceof DuelTimeline.Entry.DecisionMade
                || entry instanceof DuelTimeline.Entry.TurnStarted) {
            return null;
        }
        if (entry instanceof DuelTimeline.Entry.PhaseEntered p) {
            return withPhases ? "〔进入" + phaseText(p.phase()) + "〕" : null;
        }
        if (entry instanceof DuelTimeline.Entry.CardMoved m) {
            return cardMovedLine(m);
        }
        if (entry instanceof DuelTimeline.Entry.EffectActivated e) {
            String clause = cardText.clauseOf(e.passcode(), e.description())
                    .map(CardText::brief).orElse(null);
            return sideText(e.side()) + "：发动 " + cardLabel(e.passcode())
                    + (clause == null ? "" : "：" + clause);
        }
        if (entry instanceof DuelTimeline.Entry.AttackDeclared a) {
            return sideText(a.side()) + "：攻击宣言 " + cardLabel(a.passcode())
                    + (Texts.isBlank(a.target()) ? "" : " → " + a.target());
        }
        if (entry instanceof DuelTimeline.Entry.LpChanged lp) {
            return sideText(lp.side()) + " LP " + (lp.delta() > 0 ? "+" : "") + lp.delta()
                    + "（→" + lp.lpAfter() + "）";
        }
        return null;
    }

    private String cardMovedLine(DuelTimeline.Entry.CardMoved m) {
        String who = sideText(m.side());
        String name = m.passcode() > 0 ? cardLabel(m.passcode()) : null;
        ZoneRef to = m.toZone();
        ZoneRef from = m.fromZone();
        if (to == null) {
            // 离场即从镜像消失（重载全场等罕见情形）：按"离开原区域"如实渲染
            return who + "：" + (name == null ? "一张卡" : name) + "离开 " + zoneText(from);
        }
        return switch (to.kind()) {
            case HAND -> from == null
                    // 来源不可见（卡组无实例）：可能是抽卡也可能是检索/效果加入，diff 层区分不了，不说死
                    ? who + "：" + (name == null ? "有卡加入手牌（内容不可见）" : name + " 加入手牌")
                    : who + "：" + (name == null ? "一张卡" : name) + " 回到手牌";
            case MAIN_MONSTER_ZONE, EXTRA_MONSTER_ZONE -> monsterMove(m, who, name);
            case SPELL_TRAP_ZONE, PENDULUM_ZONE -> backrowMove(m, who, name);
            case FIELD_ZONE -> who + "：" + (name == null ? "盖放场地魔法（内容不可见）"
                    : (isFaceDown(m.position()) ? "盖放场地魔法 " + name : "场地魔法 " + name + " 上场"))
                    + "（" + zoneText(to) + "）";
            case GRAVEYARD -> who + "：" + (name == null ? "一张卡" : name)
                    + " 进入墓地（从 " + zoneText(from) + "）";
            case BANISHED -> who + "：" + (name == null ? "一张卡" : name)
                    + " 被除外（从 " + zoneText(from) + "）";
            case DECK -> who + "：" + (name == null ? "一张卡" : name) + " 回到卡组";
            case EXTRA_DECK -> who + "：" + (name == null ? "一张卡" : name) + " 加入额外卡组";
        };
    }

    private String monsterMove(DuelTimeline.Entry.CardMoved m, String who, String name) {
        String to = zoneText(m.toZone());
        // 同格移动 + 表示形式变化 = 改形（召唤/盖放是跨区域移动，不会走到这里）
        if (m.fromZone() != null && m.fromZone().equals(m.toZone())) {
            return who + "：" + (name == null ? "一只怪兽" : name)
                    + " 改变表示形式（→ " + positionText(m.position()) + "）";
        }
        if (name == null) {
            return who + "：" + (isFaceDown(m.position())
                    ? "盖放怪兽到 " + to + "（内容不可见）"
                    : "一只怪兽上场（" + to + "，身份不可见）");
        }
        return who + "：" + (isFaceDown(m.position())
                ? "把 " + name + " 盖放到 " + to + "（里侧）"
                : "把 " + name + " 放上 " + to + "（" + positionText(m.position()) + "）");
    }

    private String backrowMove(DuelTimeline.Entry.CardMoved m, String who, String name) {
        String to = zoneText(m.toZone());
        if (m.fromZone() != null && m.fromZone().equals(m.toZone())) {
            return who + "：" + (name == null ? "一张魔陷" : name)
                    + " 改变表示形式（→ " + positionText(m.position()) + "）";
        }
        if (isFaceDown(m.position())) {
            return who + "：" + (name == null
                    ? "盖放魔陷到 " + to + "（内容不可见）"
                    : "把 " + name + " 盖放到 " + to);
        }
        return who + "：" + (name == null ? "一张魔陷" : name) + " 上场（" + to + "）";
    }


    /** 卡的可读名：身份不可见给"未知卡"，卡库无资料时保留卡号，绝不编名字。 */
    private String cardLabel(long passcode) {
        if (passcode <= 0) {
            return "未知卡";
        }
        return facts.byPasscode(passcode)
                .map(CardFact::name)
                .orElseGet(() -> "卡(" + passcode + ")");
    }

    private String zoneText(ZoneRef zone) {
        return zone == null ? "未知区域" : ZoneCodes.label(zone);
    }

    private String sideText(DuelTimeline.Side side) {
        return switch (side) {
            case SELF -> "我方";
            case OPPONENT -> "对方";
            case SYSTEM -> "";
        };
    }

    private static boolean isFaceDown(String position) {
        return position != null && position.contains("DOWN");
    }

    private String positionText(String position) {
        if (position == null) {
            return "表示形式未知";
        }
        if (position.contains("DOWN")) {
            return "里侧";
        }
        return switch (position) {
            case "FACE_UP_ATTACK" -> "表侧攻击";
            case "FACE_UP_DEFENSE" -> "表侧守备";
            default -> position;
        };
    }

    private String phaseText(String phase) {
        return switch (phase) {
            case "DRAW_PHASE" -> "抽卡阶段";
            case "STANDBY_PHASE" -> "准备阶段";
            case "MAIN_PHASE_1" -> "主要阶段1";
            case "BATTLE_START_STEP", "BATTLE_STEP" -> "战斗阶段";
            case "DAMAGE_STEP", "DAMAGE_CALCULATION" -> "伤害步骤";
            case "BATTLE_END_STEP" -> "战斗结束步骤";
            case "MAIN_PHASE_2" -> "主要阶段2";
            case "END_PHASE" -> "结束阶段";
            default -> String.valueOf(phase);
        };
    }

    /** 回合头部的回合方后缀（"（我方回合）"）；记录里没有回合开始事件时留空。 */
    private String turnHeaderSuffix(List<DuelTimeline.Entry> entries) {
        return entries.stream()
                .filter(e -> e instanceof DuelTimeline.Entry.TurnStarted)
                .map(e -> "（" + sideText(((DuelTimeline.Entry.TurnStarted) e).side()) + "回合）")
                .findFirst()
                .orElse("");
    }

    private String clipNames(List<String> names) {
        String shown = String.join("、", names.subList(0, Math.min(names.size(), SUMMARY_NAME_LIMIT)));
        return names.size() > SUMMARY_NAME_LIMIT ? shown + " 等" : shown;
    }

    private static List<DuelTimeline.Entry> dropped(List<DuelTimeline.Entry> entries) {
        return entries.stream()
                .filter(e -> !(e instanceof DuelTimeline.Entry.DecisionMade))
                .toList();
    }
}
