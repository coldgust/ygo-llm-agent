package com.github.coldgust.ygoprosdk.smoke;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;

import com.github.coldgust.ygodomain.zone.ZoneRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * 决策点的人类可读描述 + 决策指令解析（供外部决策者通过文件交互）。
 *
 * <p>交互协议：把决策点描述写入 {@code /tmp/sdk-choice.txt} 并删除决策文件；
 * 决策者在 {@code /tmp/sdk-decision.txt} 写一行指令；300 秒未写则走保底。
 * 指令格式见 {@link #parse}。
 */
final class InteractiveBridge {

    static final Path CHOICE_FILE = Path.of("/tmp/sdk-choice.txt");
    static final Path DECISION_FILE = Path.of("/tmp/sdk-decision.txt");
    static final Path NAMES_FILE = Path.of("/tmp/ygo-names.tsv");
    private static final long DECISION_TIMEOUT_MS = 300_000;

    private final Map<Long, String> names = new HashMap<>();

    InteractiveBridge() {
        try {
            for (String line : Files.readAllLines(NAMES_FILE, StandardCharsets.UTF_8)) {
                int bar = line.indexOf('|');
                if (bar > 0) {
                    try {
                        names.put(Long.parseLong(line.substring(0, bar)), line.substring(bar + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[提示] 未找到卡名表 " + NAMES_FILE + "，将以 passcode 显示");
        }
        System.out.println("[卡名表] 已加载 " + names.size() + " 条");
    }

    String nameOf(CardIdentity identity) {
        if (identity.isUnknown()) {
            return "未知卡";
        }
        long code = identity.passcode();
        String name = names.get(code);
        return (name == null ? "?" : name) + "(" + code + ")";
    }

    /* ================= 描述 ================= */

    String describe(DuelState state, ChoiceRequest request) {
        StringBuilder b = new StringBuilder();
        b.append("\n===== 决策点 =====\n");
        b.append(String.format("[局面] 回合%d %s %s | 我方 LP %d 手牌%d 怪兽%d | 对方 LP %d 手牌%d 张 怪兽%d%n",
                state.turnNumber(), state.turnPlayer(), state.phase(),
                state.self().lifePoints(), state.self().handCount(), state.monsters(PlayerSide.SELF).size(),
                state.opponent().lifePoints(), state.opponent().handCount(), state.monsters(PlayerSide.OPPONENT).size()));
        b.append("[").append(request.getClass().getSimpleName().replace("Request", "")).append("]\n");
        appendRequest(b, request);
        return b.toString();
    }

    private void appendRequest(StringBuilder b, ChoiceRequest request) {
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            appendChoices(b, "可通常召唤", m.normalSummonable());
            appendChoices(b, "可特殊召唤", m.specialSummonable());
            appendChoices(b, "可改表示形式", m.repositionable());
            appendChoices(b, "可盖放怪兽", m.monsterSettable());
            appendChoices(b, "可盖放魔陷", m.spellTrapSettable());
            if (!m.activatable().isEmpty()) {
                b.append("可发动:\n");
                for (int i = 0; i < m.activatable().size(); i++) {
                    var e = m.activatable().get(i);
                    b.append(String.format("  [%d] %s desc=%d%n", i, nameOf(e.card().identity()), e.description()));
                }
            }
            b.append(String.format("可进战阶=%d 可结束回合=%d%n", m.canEnterBattlePhase() ? 1 : 0, m.canEndTurn() ? 1 : 0));
            b.append("→ 指令: main summon <i> | special <i> | reposition <i> | mset <i> | sset <i> | activate <i> | bp | end\n");
        } else if (request instanceof ChoiceRequest.BattlePhaseRequest bt) {
            if (!bt.activatable().isEmpty()) {
                b.append("可发动:\n");
                for (int i = 0; i < bt.activatable().size(); i++) {
                    var e = bt.activatable().get(i);
                    b.append(String.format("  [%d] %s desc=%d%n", i, nameOf(e.card().identity()), e.description()));
                }
            }
            if (!bt.attackable().isEmpty()) {
                b.append("可攻击:\n");
                for (int i = 0; i < bt.attackable().size(); i++) {
                    var a = bt.attackable().get(i);
                    b.append(String.format("  [%d] %s%s%n", i, nameOf(a.card().identity()), a.directAttack() ? "（可直击）" : ""));
                }
            }
            b.append(String.format("可进M2=%d 可结束战斗=%d%n", bt.canEnterMainPhase2() ? 1 : 0, bt.canEndBattlePhase() ? 1 : 0));
            b.append("→ 指令: battle attack <i> | activate <i> | m2 | end\n");
        } else if (request instanceof ChoiceRequest.ChainRequest c) {
            for (int i = 0; i < c.options().size(); i++) {
                var o = c.options().get(i);
                b.append(String.format("  [%d] %s desc=%d%s%n", i, nameOf(o.effect().card().identity()),
                        o.effect().description(), o.forced() ? "  ← 强制发动" : ""));
            }
            b.append("→ 指令: chain respond <i> | chain decline\n");
        } else if (request instanceof ChoiceRequest.CardSelectRequest s) {
            appendChoices(b, "候选（选 " + s.min() + "–" + s.max() + " 张" + (s.cancelable() ? "，可取消" : "") + "）", s.candidates());
            b.append("→ 指令: card <i> <i> ... | cancel\n");
        } else if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            b.append("候选（选 ").append(t.min()).append("–").append(t.max()).append(" 个祭品）:\n");
            for (int i = 0; i < t.candidates().size(); i++) {
                var tc = t.candidates().get(i);
                b.append(String.format("  [%d] %s（提供%d个祭品）%n", i, nameOf(tc.card().identity()), tc.releaseCount()));
            }
            b.append("→ 指令: tribute <i> <i> ... | cancel\n");
        } else if (request instanceof ChoiceRequest.UnselectRequest u) {
            appendChoices(b, "可选中", u.selectable());
            appendChoices(b, "已选中（可反悔）", u.selected());
            b.append("→ 指令: unselect select <i> | unselect unselect <i> | unselect finish | cancel\n");
        } else if (request instanceof ChoiceRequest.SumRequest sum) {
            appendChoices(b, "必选（等级和=" + sum.targetSum() + "）", sum.mustSelect());
            appendChoices(b, "可选", sum.optional());
            b.append("→ 指令: sum <必选i...> + <可选i...>（如: sum 0 / sum 0 1 2）\n");
        } else if (request instanceof ChoiceRequest.PlaceRequest p) {
            b.append("选 ").append(p.count()).append(" 个区域:\n");
            for (int i = 0; i < p.availableZones().size(); i++) {
                b.append(String.format("  [%d] %s%n", i, p.availableZones().get(i)));
            }
            b.append("→ 指令: place <i> <i>...\n");
        } else if (request instanceof ChoiceRequest.DisfieldRequest d) {
            b.append("禁用 ").append(d.count()).append(" 个区域:\n");
            for (int i = 0; i < d.availableZones().size(); i++) {
                b.append(String.format("  [%d] %s%n", i, d.availableZones().get(i)));
            }
            b.append("→ 指令: place <i>...\n");
        } else if (request instanceof ChoiceRequest.PositionRequest pos) {
            b.append("卡: ").append(nameOf(pos.card().identity())).append("\n");
            b.append("可选表示形式: ").append(pos.positions()).append("\n");
            b.append("→ 指令: pos FUA(表攻) | FDA(里攻) | FUD(表守) | FDD(里守)\n");
        } else if (request instanceof ChoiceRequest.EffectYesNoRequest eyn) {
            b.append("是否发动: ").append(nameOf(eyn.card().identity())).append(" desc=").append(eyn.description()).append("\n");
            b.append("→ 指令: yes | no\n");
        } else if (request instanceof ChoiceRequest.YesNoRequest yn) {
            b.append("是否？desc=").append(yn.description()).append("\n");
            b.append("→ 指令: yes | no\n");
        } else if (request instanceof ChoiceRequest.OptionRequest opt) {
            b.append("分支:\n");
            for (int i = 0; i < opt.optionDescriptions().size(); i++) {
                b.append(String.format("  [%d] desc=%d%n", i, opt.optionDescriptions().get(i)));
            }
            b.append("→ 指令: opt <i>\n");
        } else if (request instanceof ChoiceRequest.DeclareRaceRequest race) {
            b.append("宣言 ").append(race.count()).append(" 个种族，可选: ").append(race.available()).append("\n");
            b.append("→ 指令: race <RACE1> <RACE2>（枚举名）\n");
        } else if (request instanceof ChoiceRequest.DeclareAttributeRequest attr) {
            b.append("宣言 ").append(attr.count()).append(" 个属性，可选: ").append(attr.available()).append("\n");
            b.append("→ 指令: attrib <ATTR1> ...\n");
        } else if (request instanceof ChoiceRequest.DeclareCardRequest card) {
            b.append("宣言卡名（条件 opcodes: ").append(card.opcodes()).append("；空 = 自由宣言）\n");
            b.append("→ 指令: declare <passcode>\n");
        } else if (request instanceof ChoiceRequest.DeclareNumberRequest number) {
            b.append("宣言数字: ");
            for (int i = 0; i < number.numbers().size(); i++) {
                b.append(String.format("[%d]=%d ", i, number.numbers().get(i)));
            }
            b.append("\n→ 指令: num <i>\n");
        } else if (request instanceof ChoiceRequest.SortRequest sort) {
            appendChoices(b, "排序（给出新顺序）", sort.cards());
            b.append("→ 指令: sort <i> <i> ...\n");
        } else if (request instanceof ChoiceRequest.CounterRequest counter) {
            b.append("移除 ").append(counter.total()).append(" 个指示物（类型 ").append(counter.counterType()).append("）:\n");
            for (int i = 0; i < counter.candidates().size(); i++) {
                var cc = counter.candidates().get(i);
                b.append(String.format("  [%d] %s（现有 %d）%n", i, nameOf(cc.card().identity()), cc.available()));
            }
            b.append("→ 指令: counter <i>:<数量> ...\n");
        }
    }

    private void appendChoices(StringBuilder b, String label, List<ChoiceRequest.CardChoice> choices) {
        if (choices.isEmpty()) {
            return;
        }
        b.append(label).append(":\n");
        for (int i = 0; i < choices.size(); i++) {
            var c = choices.get(i);
            b.append(String.format("  [%d] %s @%s%n", i, nameOf(c.identity()), describeZone(c)));
        }
    }

    private String describeZone(ChoiceRequest.CardChoice c) {
        var z = c.zone();
        String base = switch (z.kind()) {
            case HAND -> "手牌";
            case MAIN_MONSTER_ZONE -> "怪兽区" + z.sequence();
            case EXTRA_MONSTER_ZONE -> "额外怪兽区" + z.sequence();
            case SPELL_TRAP_ZONE -> "魔陷区" + z.sequence();
            case PENDULUM_ZONE -> "灵摆" + (z.sequence() == 0 ? "左" : "右");
            case FIELD_ZONE -> "场地";
            case GRAVEYARD -> "墓地";
            case BANISHED -> "除外";
            case DECK -> "卡组";
            case EXTRA_DECK -> "额外卡组";
        };
        return base + (z.owner() == PlayerSide.SELF ? "" : "(对方)");
    }

    /* ================= 决策文件交互 ================= */

    void ask(String dump) throws InterruptedException {
        try {
            Files.deleteIfExists(DECISION_FILE);
            Files.writeString(CHOICE_FILE, dump, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println(dump);
        }
        long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(DECISION_FILE)) {
                try {
                    List<String> lines = Files.readAllLines(DECISION_FILE, StandardCharsets.UTF_8);
                    Files.deleteIfExists(DECISION_FILE);
                    for (String line : lines) {
                        if (!line.isBlank()) {
                            System.out.println("[收到决策] " + line.trim());
                            lastDecision = line.trim();
                            return;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            Thread.sleep(200);
        }
        System.out.println("[决策超时，走保底]");
        lastDecision = null;
    }

    private volatile String lastDecision;

    String lastDecision() {
        String d = lastDecision;
        lastDecision = null;
        return d;
    }

    /* ================= 指令解析 ================= */

    ChoiceSelection parse(ChoiceRequest request, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] t = line.trim().split("\\s+");
        // 文案示例带 "main summon" / "battle attack" 前缀；解析按动词匹配，这里剥掉前缀以兼容
        if (t.length > 1 && (t[0].equalsIgnoreCase("main") || t[0].equalsIgnoreCase("battle"))) {
            t = Arrays.copyOfRange(t, 1, t.length);
        }
        try {
            if (request instanceof ChoiceRequest.MainPhaseRequest m) {
                return switch (t[0]) {
                    case "summon" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Summon(m.normalSummonable().get(idx(t[1]))));
                    case "special" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SpecialSummon(m.specialSummonable().get(idx(t[1]))));
                    case "reposition" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Reposition(m.repositionable().get(idx(t[1]))));
                    case "mset" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetMonster(m.monsterSettable().get(idx(t[1]))));
                    case "sset" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetSpellTrap(m.spellTrapSettable().get(idx(t[1]))));
                    case "activate" -> new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Activate(m.activatable().get(idx(t[1]))));
                    case "bp" -> new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EnterBattlePhase.INSTANCE);
                    case "end" -> new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE);
                    default -> null;
                };
            }
            if (request instanceof ChoiceRequest.BattlePhaseRequest bt) {
                return switch (t[0]) {
                    case "attack" -> new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.Attack(bt.attackable().get(idx(t[1]))));
                    case "activate" -> new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.BattleActivate(bt.activatable().get(idx(t[1]))));
                    case "m2" -> new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EnterMainPhase2.INSTANCE);
                    case "end" -> new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EndBattlePhase.INSTANCE);
                    default -> null;
                };
            }
            if (request instanceof ChoiceRequest.ChainRequest chain) {
                if (t[0].equals("chain") && t.length > 1) {
                    if (t[1].equals("decline")) {
                        return new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE);
                    }
                    if (t[1].equals("respond")) {
                        return new ChoiceSelection.ChainSelection(
                                new ChoiceSelection.ChainAnswer.Respond(chain.options().get(idx(t[2]))));
                    }
                }
                return null;
            }
            if (request instanceof ChoiceRequest.CardSelectRequest s) {
                if (t[0].equals("cancel")) {
                    return ChoiceSelection.Cancel.INSTANCE;
                }
                var picked = new ArrayList<ChoiceRequest.CardChoice>();
                for (int i = 1; i < t.length; i++) {
                    picked.add(s.candidates().get(idx(t[i])));
                }
                return new ChoiceSelection.CardSelection(picked);
            }
            if (request instanceof ChoiceRequest.TributeSelectRequest tribute) {
                if (t[0].equals("cancel")) {
                    return ChoiceSelection.Cancel.INSTANCE;
                }
                var picked = new ArrayList<ChoiceRequest.TributeChoice>();
                for (int i = 1; i < t.length; i++) {
                    picked.add(tribute.candidates().get(idx(t[i])));
                }
                return new ChoiceSelection.TributeSelection(picked);
            }
            if (request instanceof ChoiceRequest.UnselectRequest u) {
                return switch (t[0]) {
                    case "unselect" -> t.length > 2 && t[1].equals("select")
                            ? new ChoiceSelection.UnselectSelection(new ChoiceSelection.UnselectStep.Select(u.selectable().get(idx(t[2]))))
                            : new ChoiceSelection.UnselectSelection(new ChoiceSelection.UnselectStep.Unselect(u.selected().get(idx(t[2]))));
                    case "finish" -> new ChoiceSelection.UnselectSelection(ChoiceSelection.UnselectStep.Finish.INSTANCE);
                    case "cancel" -> ChoiceSelection.Cancel.INSTANCE;
                    default -> null;
                };
            }
            if (request instanceof ChoiceRequest.SumRequest sum) {
                var picked = new ArrayList<ChoiceRequest.CardChoice>();
                for (int i = 1; i < t.length; i++) {
                    int idx = idx(t[i]);
                    picked.add(idx < sum.mustSelect().size()
                            ? sum.mustSelect().get(idx)
                            : sum.optional().get(idx - sum.mustSelect().size()));
                }
                return new ChoiceSelection.SumSelection(picked);
            }
            if (request instanceof ChoiceRequest.PlaceRequest p) {
                var zones = new ArrayList<ZoneRef>();
                for (int i = 1; i < t.length; i++) {
                    zones.add(p.availableZones().get(idx(t[i])));
                }
                return new ChoiceSelection.PlaceSelection(zones);
            }
            if (request instanceof ChoiceRequest.DisfieldRequest d) {
                var zones = new ArrayList<ZoneRef>();
                for (int i = 1; i < t.length; i++) {
                    zones.add(d.availableZones().get(idx(t[i])));
                }
                return new ChoiceSelection.PlaceSelection(zones);
            }
            if (request instanceof ChoiceRequest.PositionRequest) {
                String token = t[0].equalsIgnoreCase("pos") && t.length > 1 ? t[1] : t[0];
                var position = switch (token.toUpperCase()) {
                    case "FUA" -> Position.FACE_UP_ATTACK;
                    case "FDA" -> Position.FACE_DOWN_ATTACK;
                    case "FUD" -> Position.FACE_UP_DEFENSE;
                    case "FDD" -> Position.FACE_DOWN_DEFENSE;
                    default -> null;
                };
                return position == null ? null : new ChoiceSelection.PositionSelection(position);
            }
            if (request instanceof ChoiceRequest.EffectYesNoRequest) {
                return new ChoiceSelection.EffectYesNoSelection(t[0].equals("yes"));
            }
            if (request instanceof ChoiceRequest.YesNoRequest) {
                return new ChoiceSelection.YesNoSelection(t[0].equals("yes"));
            }
            if (request instanceof ChoiceRequest.OptionRequest) {
                return new ChoiceSelection.OptionSelection(idx(t[0]));
            }
            if (request instanceof ChoiceRequest.DeclareRaceRequest) {
                var races = new LinkedHashSet<MonsterRace>();
                for (int i = 1; i < t.length; i++) {
                    races.add(MonsterRace.valueOf(t[i].toUpperCase()));
                }
                return new ChoiceSelection.DeclareRaceSelection(races);
            }
            if (request instanceof ChoiceRequest.DeclareAttributeRequest) {
                var attributes = new LinkedHashSet<Attribute>();
                for (int i = 1; i < t.length; i++) {
                    attributes.add(Attribute.valueOf(t[i].toUpperCase()));
                }
                return new ChoiceSelection.DeclareAttributeSelection(attributes);
            }
            if (request instanceof ChoiceRequest.DeclareCardRequest) {
                return new ChoiceSelection.DeclareCardSelection(Long.parseLong(t[0]));
            }
            if (request instanceof ChoiceRequest.DeclareNumberRequest) {
                return new ChoiceSelection.DeclareNumberSelection(idx(t[0]));
            }
            if (request instanceof ChoiceRequest.SortRequest sort) {
                var ordered = new ArrayList<ChoiceRequest.CardChoice>();
                for (int i = 1; i < t.length; i++) {
                    ordered.add(sort.cards().get(idx(t[i])));
                }
                return new ChoiceSelection.SortSelection(ordered);
            }
            if (request instanceof ChoiceRequest.CounterRequest counter) {
                var removals = new LinkedHashMap<ChoiceRequest.CardChoice, Integer>();
                for (int i = 1; i < t.length; i++) {
                    String[] kv = t[i].split(":");
                    removals.put(counter.candidates().get(idx(kv[0])).card(), Integer.parseInt(kv[1]));
                }
                return new ChoiceSelection.CounterSelection(removals);
            }
        } catch (Exception e) {
            System.out.println("[指令解析失败] " + e);
            return null;
        }
        return null;
    }

    private static int idx(String token) {
        return Integer.parseInt(token.replace("[", "").replace("]", ""));
    }
}
