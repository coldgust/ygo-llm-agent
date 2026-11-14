package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.support.FoldedLines;
import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Deque;
import java.util.List;

/**
 * 决斗记录流的逐事件渲染：把 {@link DuelTimeline} 的条目翻成给模型看的过程行。
 *
 * <p>四条硬约定，结构上由本类保证：
 * <ul>
 *   <li>「发动」只表示<b>已发动</b>、不表示已生效——协议不逐环推送结算结果，
 *       渲染层不替模型下"已生效"结论（实机事故：把"发动了灰流丽"当成"幕帘已被无效"）。</li>
 *   <li>效果描述码必须经 {@link CardText} 解条款，绝不输出裸效果码。</li>
 *   <li>身份不可见的卡渲染"内容不可见"，绝不猜。</li>
 *   <li>**逐事件原文直推，不聚合**（2026-11-14 用户指令）：回合摘要与"等"式折叠是信息损失
 *       ——渲染层不总结、不计数、不省略；回合边界由 {@code TurnStarted} 渲染成边界行。</li>
 * </ul>
 *
 * <p>唯一出口 {@link #lines}：供 {@code DuelRecordFeed} 拉取增量后逐行写进 memory 的
 * RECORD 条目（永不修剪、只增不减）。我方提交过的决策
 * （{@link DuelTimeline.Entry.DecisionMade}）不在这里渲染：我方动作由 feed 的应答行
 * （带决策点语境）承载，重复反而会淹没"对方做了什么"。
 */
public final class DuelRecordSections {

    private final CardFacts facts;
    private final CardText cardText;

    public DuelRecordSections(CardFacts facts, CardText cardText) {
        this.facts = facts;
        this.cardText = cardText;
    }

    /** 增量条目 → 逐事件行（回合边界行、阶段行、移动/发动/攻击/LP 行；连续同文行折叠 ×N）。 */
    public List<String> lines(List<DuelTimeline.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (DuelTimeline.Entry entry : resolveReveals(entries)) {
            String line = line(entry);
            if (line == null) {
                continue;
            }
            // 连续同文行折叠 ×N：对手一次抽 3 张是 3 条同文事件，逐条只会刷屏（同文折叠不损失语义）
            FoldedLines.add(out, line);
        }
        return out;
    }

    /**
     * 把"亮牌"并进它前面那条"未知进手"。
     *
     * <p>协议顺序是：进手牌的 {@code MSG_MOVE}（卡号被抹零）→ 晚一两帧才到的
     * {@code MSG_CONFIRM_CARDS}（真实卡号）。事件是追加的，前者写下去时还不知道是什么卡，
     * 所以在这里做一次回填：未知进手 + 随后同侧的公开 = 一条「X 加入手牌」，
     * 亮牌条目本身不再单列（没配上的才单列，比如"确认手牌"这类）。
     */
    private static List<DuelTimeline.Entry> resolveReveals(List<DuelTimeline.Entry> entries) {
        List<DuelTimeline.Entry> out = new ArrayList<>(entries.size());
        Deque<Integer> pending = new ArrayDeque<>();
        for (DuelTimeline.Entry entry : entries) {
            if (entry instanceof DuelTimeline.Entry.CardMoved m && isUnknownHandAdd(m)) {
                pending.addLast(out.size());
                out.add(entry);
                continue;
            }
            if (entry instanceof DuelTimeline.Entry.CardRevealed r) {
                Integer index = pending.pollFirst();
                if (index != null && out.get(index).side() == r.side()) {
                    DuelTimeline.Entry.CardMoved m = (DuelTimeline.Entry.CardMoved) out.get(index);
                    out.set(index, new DuelTimeline.Entry.CardMoved(m.seq(), m.turn(), m.side(),
                            r.passcode(), m.fromZone(), m.toZone(), m.position()));
                    continue;
                }
                if (index != null) {
                    pending.addFirst(index);
                }
            }
            out.add(entry);
        }
        return out;
    }

    private static boolean isUnknownHandAdd(DuelTimeline.Entry.CardMoved m) {
        return m.toZone() != null && m.toZone().kind() == ZoneKind.HAND
                && m.passcode() == 0 && m.fromZone() == null;
    }

    /** 单条目 → 一行；null 表示该条目不出现在行里（我方决策由 feed 应答行承载）。 */
    private String line(DuelTimeline.Entry entry) {
        if (entry instanceof DuelTimeline.Entry.DecisionMade) {
            return null;
        }
        if (entry instanceof DuelTimeline.Entry.TurnStarted t) {
            // 回合边界行：记录流的分界（直推全文后这是唯一的回合结构标记）
            return "〔第 " + t.turn() + " 回合 · " + sideText(t.side()) + "回合〕";
        }
        if (entry instanceof DuelTimeline.Entry.PhaseEntered p) {
            return "〔进入" + phaseText(p.phase()) + "〕";
        }
        if (entry instanceof DuelTimeline.Entry.CardMoved m) {
            return cardMovedLine(m);
        }
        if (entry instanceof DuelTimeline.Entry.EffectActivated e) {
            String head = linkPrefix(e.chainIndex()) + sideText(e.side()) + "：发动 "
                    + cardLabel(e.passcode());
            Optional<String> clause = cardText.firedClause(e.passcode(), e.description());
            if (clause.isPresent()) {
                // 描述码可解析（或单条款卡）：给那一条正文，就是准确答案
                return head + "：" + CardText.brief(clause.get());
            }
            // 多条款卡解不出条款（MDPro3 分叉的 CHAINING desc 是类别值，不带效果序号）：
            // 给 来源区域 + 全部条款全文，让模型/复盘自己判断发动了哪条——
            // 创造的 ① 场上·手卡发动、② 墓地发动，区域就能分开；绝不猜一条冒充定位
            List<String> clauses = cardText.clauses(e.passcode());
            if (clauses.isEmpty()) {
                return head;
            }
            String where = e.sourceZone() == null ? ""
                    : "（" + ZoneCodes.label(e.sourceZone()) + "）";
            return head + where + "：" + cardText.all(clauses);
        }
        if (entry instanceof DuelTimeline.Entry.CardRevealed r) {
            // 没配上"加入手牌"的公开（例如确认墓地/手牌）：单列一行，仍然是事实
            return sideText(r.side()) + "：公开 " + cardLabel(r.passcode());
        }
        if (entry instanceof DuelTimeline.Entry.ChainSolving c) {
            // "谁的效果处理了"：这一行之后到下一环之前的事实都属于这一环（MDPRO3 的"N 效果处理"）
            String who = c.passcode() > 0 ? cardLabel(c.passcode()) : "";
            return "〔连锁 " + c.chainIndex() + " 效果处理〕" + who;
        }
        if (entry instanceof DuelTimeline.Entry.ChainNegated n) {
            // MDPRO3 的"N 效果处理 → 效果无效"：被无效的那一环没有结算，
            // 这一行之后不该有任何"它生效了"的事实（检索没入手、破坏没发生）
            String who = n.passcode() > 0 ? cardLabel(n.passcode()) : "";
            return "〔连锁 " + n.chainIndex() + " 效果无效〕" + who;
        }
        if (entry instanceof DuelTimeline.Entry.ChainEnded) {
            return "〔处理结束〕";
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

    /** 连锁环数前缀：只有第 2 环起才标（第 1 环就是普通发动，标了反而吵）。 */
    private static String linkPrefix(Integer chainIndex) {
        return chainIndex == null || chainIndex <= 1 ? "" : "〔连锁 " + chainIndex + "〕";
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
            // 魔陷区/灵摆区没有"表示形式"：盖牌变成表侧只意味着它被发动（或效果翻开），
            // 那是"发动"行/我方应答行的事；写成"改变表示形式（→ 表侧攻击）"是事实错误
            // （2026-11-14 实测：记录里出现"禁忌的一滴 改变表示形式"，而它是速攻魔法）。
            if (!isFaceDown(m.position())) {
                return null;
            }
            // 反过来（表侧 → 里侧）确实是发生了什么，得说出来，否则模型以为它还开着
            return who + "：" + (name == null ? "一张魔陷" : name) + " 被翻回里侧";
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
                .map(f -> f.name())
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
        return PhaseText.labelOf(phase);
    }
}
