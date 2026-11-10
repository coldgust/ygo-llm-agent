package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 局面段的逐行渲染：手牌 / 场面 / 墓地与除外 / 场地槽位。
 * 只做确定性陈述，不做判断；卡名一律经 {@link DuelMathService#labelOf}（卡库无资料时保留卡号，绝不编名字）。
 */
final class BoardSections {

    private final CardFacts facts;

    BoardSections(CardFacts facts) {
        this.facts = facts;
    }

    /**
     * {@code [本回合可用性]}：把手牌里"这一回合发不出来的魔法卡"直接点名。
     */
    void appendHandSpellUsability(StringBuilder sb, DuelState state) {
        List<CardInstance> hand = state.player(PlayerSide.SELF).hand();
        List<String> spells = new ArrayList<>();
        for (CardInstance card : hand) {
            if (card.identity() == null || card.identity().passcode() <= 0) {
                continue;
            }
            facts.byPasscode(card.identity().passcode())
                    .filter(fact -> !fact.isMonster())
                    .map(CardFact::cardType)
                    .filter(type -> type != null && type.contains("魔法"))
                    .ifPresent(type -> spells.add(DuelMathService.labelOf(card, facts) + "〔" + type + "〕"));
        }
        if (spells.isEmpty()) {
            return;
        }
        sb.append("[本回合可用性] ").append(String.join("、", spells)).append("：");
        if (state.isMyTurn()) {
            sb.append("魔法卡只能在自己回合的主要阶段发动。留到对手回合 = 用不了；")
                    .append("要拿它当对手回合的阻抗，必须在结束回合前盖到魔陷区。\n");
        } else {
            sb.append("现在是对手回合，手牌里的魔法卡一律发不出来（只有已盖的速攻魔法/陷阱、")
                    .append("卡面写明可从手牌发动的怪兽才算阻抗）。\n");
        }
    }

    String handLine(DuelState state, PlayerSide side) {
        List<CardInstance> hand = state.player(side).hand();
        if (hand.isEmpty()) {
            return "（无）";
        }
        StringJoiner sj = new StringJoiner("、");
        hand.forEach(c -> sj.add(DuelMathService.labelOf(c, facts) + typeTagOf(c)));
        return sj.toString();
    }

    private String typeTagOf(CardInstance card) {
        if (card == null || card.identity() == null || card.identity().passcode() <= 0) {
            return "";
        }
        return facts.byPasscode(card.identity().passcode())
                .filter(fact -> !fact.isMonster())
                .map(CardFact::cardType)
                .filter(type -> type != null && !type.isBlank())
                .map(type -> "〔" + type + "〕")
                .orElse("");
    }

    /**
     * [场地槽位]：逐格列出双方怪兽区/魔陷区/场地/额外怪兽区的占用，编码与位置决策里的编号一致。
     *
     * <p>没有这一块，"位置"就没法成为决策：模型只知道"有 5 个空格"，
     * 不知道哪格空着、自己场上的卡在第几格、额外怪兽区占没占——它只能瞎写或干脆不写。
     * 列的是**格子**而不是"一堆卡名"，因为位置决策问的就是格子。
     */
    String boardSlotsSection(DuelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[场地槽位] 编码即位置（序号从 1 起；空 = 可放置）。写计划 zone 或选位置时用编码，如 MMZ-3：\n");
        for (PlayerSide side : List.of(PlayerSide.SELF, PlayerSide.OPPONENT)) {
            String who = side == PlayerSide.SELF ? "我方" : "对方";
            sb.append("  ").append(who)
                    .append(" MMZ: ").append(slots(state, side, ZoneKind.MAIN_MONSTER_ZONE, 5))
                    .append(" ｜ EMZ: ").append(slots(state, side, ZoneKind.EXTRA_MONSTER_ZONE, 2))
                    .append(" ｜ FZ: ").append(slotName(state, new ZoneRef(ZoneKind.FIELD_ZONE, side, 0)))
                    .append("\n");
            sb.append("       STZ: ").append(slots(state, side, ZoneKind.SPELL_TRAP_ZONE, 5))
                    .append(" ｜ PZ: ").append(slots(state, side, ZoneKind.PENDULUM_ZONE, 2))
                    .append("\n");
        }
        return sb.toString();
    }

    private String slots(DuelState state, PlayerSide side, ZoneKind kind, int count) {
        StringJoiner sj = new StringJoiner(" ");
        for (int seq = 0; seq < count; seq++) {
            sj.add((seq + 1) + slotName(state, new ZoneRef(kind, side, seq)));
        }
        return sj.toString();
    }

    /** 单格：空 / 卡名（里侧只说"盖卡"，不编造身份）。 */
    private String slotName(DuelState state, ZoneRef zone) {
        return state.cardAt(zone).map(c -> {
            if (c.isUnknown()) {
                return "盖卡";
            }
            return facts.byPasscode(c.passcode()).map(f -> Texts.flattenClip(f.name(), 10))
                    .orElseGet(() -> "卡(" + c.passcode() + ")");
        }).orElse("空");
    }

    String fieldLine(DuelState state, PlayerSide side) {
        StringJoiner sj = new StringJoiner("；");
        List<CardInstance> monsters = state.monsters(side);
        if (monsters.isEmpty()) {
            sj.add("怪兽区（空）");
        } else {
            StringJoiner mj = new StringJoiner("、");
            monsters.forEach(c -> mj.add(monsterLabel(c)));
            sj.add("怪兽：" + mj);
        }
        List<CardInstance> backrow = state.spellTraps(side);
        if (backrow.isEmpty()) {
            sj.add("魔陷区（空）");
        } else {
            StringJoiner bj = new StringJoiner("、");
            backrow.forEach(c -> {
                boolean known = !c.isUnknown();
                String name = known ? DuelMathService.labelOf(c, facts) : "盖卡";
                if (c.position() == null) {
                    // 朝向未同步：别硬说里侧（表侧永续魔陷很常见），如实标注
                    bj.add(known ? name + "[朝向未知]" : "盖卡");
                } else if (c.position().isFaceUp()) {
                    bj.add(name);
                } else {
                    bj.add(known ? name + "(里侧)" : "盖卡");
                }
            });
            sj.add("魔陷：" + bj);
        }
        state.fieldSpell(side).ifPresent(field ->
                sj.add("场地：" + DuelMathService.labelOf(field, facts)));
        return sj.toString();
    }

    private String monsterLabel(CardInstance c) {
        if (c.position() == null) {
            // 表示形式未同步：身份已知也别猜"里侧"，如实标注
            return c.isUnknown() ? "盖卡怪"
                    : DuelMathService.labelOf(c, facts) + "[状态未知]";
        }
        if (!c.position().isFaceUp()) {
            return c.isUnknown() ? "盖卡怪"
                    : DuelMathService.labelOf(c, facts) + "[里侧]";
        }
        CardFact fact = facts.byPasscode(c.passcode()).orElse(null);
        String pos = c.position() == Position.FACE_UP_ATTACK ? "攻" : "守";
        if (fact == null) {
            return "卡(" + c.passcode() + ")[" + pos + "]";
        }
        String stats = fact.atk() == null ? "?" : String.valueOf(fact.atk());
        String def = fact.defense() == null ? "?" : String.valueOf(fact.defense());
        return fact.name() + "[" + pos + " ATK" + stats + "/DEF" + def + "]";
    }

    String graveLine(DuelState state, PlayerSide side) {
        List<CardIdentity> gy = state.player(side).graveyard();
        if (gy.isEmpty()) {
            return "墓地（空）";
        }
        StringJoiner sj = new StringJoiner("、");
        gy.forEach(id -> sj.add(DuelMathService.labelOf(
                new CardInstance(0, id, null, null, null, null), facts)));
        int banished = state.player(side).banished().size();
        return sj + "；除外 " + banished + " 张";
    }
}
