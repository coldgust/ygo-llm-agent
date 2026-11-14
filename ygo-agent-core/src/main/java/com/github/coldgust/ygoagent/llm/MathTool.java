package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.context.DuelMathService.AttackLine;
import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import com.github.coldgust.ygoagent.trace.DecisionTraceWriter;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 数学工具：战斗结算 / 伤害投影 / 抽卡概率 / 素材核对，确定性计算经 {@link DuelMathService} 纯函数完成。
 *
 * <p>局面状态是**按决策点绑定**的：{@code DuelAgent.decide()} 入口把当前 {@link DuelState}
 * 换进 volatile 快照（DuelState 不可变、整局面镜像逐事件重建，换引用即换状态，线程安全）。
 * 工具输出首行回显快照回合，模型与轨迹都能核对时效；未绑定/解析失败一律 fail-closed 明说，不猜。
 * <p>只算数不给建议：输出是事实与口径，不出现"你应该攻击"类措辞（§2.2.10 确定性边界）。
 */
public final class MathTool {

    public static final String BATTLE_CALC = "battle_calc";
    public static final String BATTLE_TABLE = "battle_table";
    public static final String DAMAGE_PROJECTION = "damage_projection";
    public static final String DRAW_ODDS = "draw_odds";
    public static final String MATERIAL_MATH = "material_math";

    private static final Logger log = LoggerFactory.getLogger(MathTool.class);

    /** 无状态纯函数引擎，全局一份即可。 */
    private static final DuelMathService MATH = new DuelMathService();

    private static final String ASSUMPTION_FOOTER =
            "（标准规则结算，未计入效果修正：贯穿/攻守增幅/多次攻击/连锁中的临时变化）";
    private static final String NO_SNAPSHOT = "（暂无局面快照：本工具需在决斗进行中调用）";

    private final CardFacts facts;
    /** 己方卡组全清单（主+额外，去重；可空 = draw_odds fail-closed）。 */
    private final List<Long> deckPasscodes;
    private final DecisionTraceWriter traceWriter;
    private volatile DuelState snapshot;

    public MathTool(CardFacts facts, List<Long> deckPasscodes, DecisionTraceWriter traceWriter) {
        this.facts = facts;
        this.deckPasscodes = deckPasscodes == null ? List.of() : List.copyOf(deckPasscodes);
        this.traceWriter = DecisionTraceWriter.noopOr(traceWriter);
    }

    /** 每个决策点绑定最新局面快照（调用线程先于任何工具调用，程序序保证可见）。 */
    public void bind(DuelState state) {
        if (state != null) {
            snapshot = state;
        }
    }

    // ---------------------------------------------------------------- 工具面

    @Tool(name = BATTLE_CALC, value = "算一次攻击结算（我方或对方视角）：给攻击方与目标的卡名或格子编码"
            + "（如 MMZ-3，配 side 参数），返回破坏/交换/被反杀与双方伤害数值。"
            + "目标留空 = 直接攻击（规则上仅对方场上无怪兽时合法）。"
            + "战斗数值禁止心算，用本工具；上下文已给出的结算不要重复调用。")
    public String battleCalc(
            @P(value = "攻击方所在侧：self（我方，默认）或 opponent（对方）", required = false)
            String side,
            @P("攻击方：卡名或格子编码，须是攻击表示怪兽") String attacker,
            @P(value = "目标：卡名或格子编码；留空 = 直接攻击", required = false) String target) {
        return traced(BATTLE_CALC, Map.of("side", safe(side), "attacker", safe(attacker),
                "target", safe(target)), () -> {
            DuelState state = snapshot();
            if (state == null) {
                return NO_SNAPSHOT;
            }
            PlayerSide attackerSide = sideOf(side);
            Lookup a = resolveMonsters(attackerSide, attacker);
            if (a.error() != null) {
                return a.error();
            }
            FieldCard attackerCard = a.card();
            if (!inMonsterZone(attackerCard.instance())) {
                return attackerCard.describe() + " 不在怪兽区，不能发起攻击";
            }
            if (attackerCard.instance().position() != Position.FACE_UP_ATTACK) {
                return attackerCard.describe() + "：不在攻击表示，不能发起攻击（结算无意义）";
            }
            boolean direct = target == null || target.isBlank()
                    || target.strip().equalsIgnoreCase("direct") || target.strip().equals("直接攻击");
            List<AttackLine> table = MATH.attackTable(state, facts, attackerSide);
            if (direct) {
                if (table.stream().anyMatch(l -> l.targetInstanceId() != null
                        && l.attackerInstanceId() == attackerCard.instance().instanceId())) {
                    return "对方场上还有怪兽，规则上不能直接攻击；用 battle_calc 指定目标"
                            + "，或用 battle_table 看全部结算";
                }
                AttackLine line = table.stream()
                        .filter(l -> l.attackerInstanceId() == attackerCard.instance().instanceId())
                        .findFirst().orElse(null);
                return line == null ? "（卡库无该卡攻击力数据，无法结算）"
                        : header(state) + "\n- " + line.describe() + "\n" + ASSUMPTION_FOOTER + "\n";
            }
            Lookup t = resolveMonsters(opposite(attackerSide), target);
            if (t.error() != null) {
                return t.error();
            }
            FieldCard targetCard = t.card();
            if (!inMonsterZone(targetCard.instance())) {
                return targetCard.describe() + " 不在怪兽区，不是攻击目标";
            }
            AttackLine line = table.stream()
                    .filter(l -> l.attackerInstanceId() == attackerCard.instance().instanceId()
                            && l.targetInstanceId() != null
                            && l.targetInstanceId() == targetCard.instance().instanceId())
                    .findFirst().orElse(null);
            if (line == null) {
                return targetCard.describe() + " 不是怪兽，或不在可被攻击的位置";
            }
            return header(state) + "\n- " + line.describe() + "\n" + ASSUMPTION_FOOTER + "\n";
        });
    }

    @Tool(name = BATTLE_TABLE, value = "列出当前全部攻击结算（按局面快照算，不问阶段）："
            + "side=self（默认）= 我方每只攻击表示怪兽 × 对方全场的逐对结算表；"
            + "side=opponent = 对方攻击怪兽对我方的威胁表。"
            + "进入战阶或评估威胁前先调它，逐对数值不要心算。")
    public String battleTable(
            @P(value = "攻击方视角：self（默认）或 opponent", required = false) String side) {
        return traced(BATTLE_TABLE, Map.of("side", safe(side)), () -> {
            DuelState state = snapshot();
            if (state == null) {
                return NO_SNAPSHOT;
            }
            PlayerSide attackerSide = sideOf(side);
            List<AttackLine> table = MATH.attackTable(state, facts, attackerSide);
            StringBuilder sb = new StringBuilder(header(state));
            sb.append("\n攻击方=").append(attackerSide == PlayerSide.SELF ? "我方" : "对方");
            if (table.isEmpty()) {
                sb.append("：没有攻击表示的怪兽（表侧攻击位为空或攻击力不可知）\n");
                return sb.toString();
            }
            for (AttackLine line : table) {
                sb.append("\n- ").append(line.describe());
            }
            sb.append("\n").append(ASSUMPTION_FOOTER).append("\n");
            return sb.toString();
        });
    }

    @Tool(name = DAMAGE_PROJECTION, value = "投影攻击全部打完后的确定伤害合计与斩杀口径："
            + "attackers 传卡名/格子编码列表（逗号分隔；留空 = 全部攻击位怪兽），"
            + "逐只按剩余目标择优结算（先打掉的不再重复计）。side=self 时合计与对方 LP 比较，"
            + "side=opponent 时合计为对方能对我方造成的确定伤害（与我方 LP 比较）。")
    public String damageProjection(
            @P(value = "攻击方视角：self（默认）或 opponent", required = false) String side,
            @P(value = "攻击者句柄列表（逗号/顿号分隔；留空 = 全部攻击位怪兽）", required = false)
            String attackers) {
        return traced(DAMAGE_PROJECTION, Map.of("side", safe(side), "attackers", safe(attackers)), () -> {
            DuelState state = snapshot();
            if (state == null) {
                return NO_SNAPSHOT;
            }
            PlayerSide attackerSide = sideOf(side);
            PlayerSide defenderSide = opposite(attackerSide);
            List<AttackLine> table = MATH.attackTable(state, facts, attackerSide);
            FieldCardList chosen = pickAttackers(state, attackerSide, attackers, table);
            if (chosen.error() != null) {
                return chosen.error();
            }
            int opponentLp = state.player(defenderSide).lifePoints();
            // 表侧已知目标才可能被确定打掉：里侧/未知目标的结算未解明，不参与"打掉后移除"
            Set<Long> remaining = new LinkedHashSet<>();
            for (CardInstance m : state.monsters(defenderSide)) {
                if (m.position() != null && m.position().isFaceUp() && !m.isUnknown()
                        && facts.byPasscode(m.passcode()).isPresent()) {
                    remaining.add(m.instanceId());
                }
            }
            StringBuilder sb = new StringBuilder(header(state));
            int total = 0;
            int unresolved = 0;
            List<String> levelDetails = new ArrayList<>();
            for (FieldCard attacker : chosen.cards()) {
                AttackLine line = bestLine(attacker, table, remaining);
                if (line == null) {
                    levelDetails.add("- " + attacker.describe() + "：无可结算目标");
                    continue;
                }
                if (line.outcome() == AttackLine.Outcome.UNRESOLVED) {
                    unresolved++;
                }
                total += line.damageToOpponent();
                if (line.targetInstanceId() != null
                        && (line.outcome() == AttackLine.Outcome.DESTROY_AND_DAMAGE
                        || line.outcome() == AttackLine.Outcome.TRADE)) {
                    remaining.remove(line.targetInstanceId());
                }
                levelDetails.add("- " + line.describe());
            }
            sb.append("\n攻击方=").append(attackerSide == PlayerSide.SELF ? "我方" : "对方")
                    .append("，防守方=").append(defenderSide == PlayerSide.SELF ? "我方" : "对方")
                    .append(" LP ").append(opponentLp);
            for (String detail : levelDetails) {
                sb.append("\n").append(detail);
            }
            sb.append("\n确定伤害合计 ").append(total);
            if (total >= opponentLp && total > 0) {
                sb.append(" ≥ LP ").append(opponentLp).append("：保守口径下可斩杀防守方");
            } else {
                sb.append(" < LP ").append(opponentLp).append("：保守口径下不足以斩杀防守方");
            }
            if (unresolved > 0) {
                sb.append("；含 ").append(unresolved)
                        .append(" 条未解明结算（盖卡/未知目标），实际伤害可能不同");
            }
            sb.append("\n").append(ASSUMPTION_FOOTER).append("\n");
            return sb.toString();
        });
    }

    @Tool(name = DRAW_ODDS, value = "算从自己主卡组抽 d 张、抽到指定卡的概率（超几何）：返回 P(≥1)、P(≥2)。"
            + "卡组内剩余张数 = 卡组清单张数 − 公开区（手/场/墓/除外）已见张数。只支持自己卡组；"
            + "参数 card 用卡名或卡号。")
    public String drawOdds(
            @P("卡名或卡号（passcode）") String card,
            @P(value = "抽取张数，默认 1", required = false) Integer draws) {
        return traced(DRAW_ODDS, Map.of("card", safe(card), "draws", String.valueOf(draws)), () -> {
            DuelState state = snapshot();
            if (state == null) {
                return NO_SNAPSHOT;
            }
            long passcode = resolvePasscode(card);
            if (passcode <= 0) {
                return "（无法解析卡：" + safe(card) + "；给卡号，或用 find_card 查准卡名）";
            }
            CardFact fact = facts.byPasscode(passcode).orElse(null);
            String name = fact == null ? "卡(" + passcode + ")" : fact.name();
            if (fact != null && fact.cardType() != null && isExtraDeckType(fact.cardType())) {
                return "「" + name + "」是额外卡组怪兽（类型 " + fact.cardType()
                        + "），额外卡组不会被抽到，抽卡概率不适用";
            }
            if (deckPasscodes.isEmpty()) {
                return "（无卡组清单数据，无法计算「" + name + "」的抽卡概率）";
            }
            int deckCopies = (int) deckPasscodes.stream().filter(p -> p == passcode).count();            if (deckCopies == 0) {
                return "（卡组清单里没有「" + name + "」（" + passcode + "））";
            }
            int visible = countVisible(state, passcode);
            int inDeck = Math.max(0, deckCopies - visible);
            int deckLeft = state.player(PlayerSide.SELF).mainDeckCount();
            if (deckLeft <= 0) {
                return "主卡组已空，抽卡概率不适用";
            }
            int d = draws == null ? 1 : Math.max(1, Math.min(draws, deckLeft));
            StringBuilder sb = new StringBuilder("「" + name + "」（卡号 " + passcode + "）：")
                    .append("清单 ").append(deckCopies).append(" 张，公开区已见 ").append(visible)
                    .append(" 张，主卡组内剩余 ").append(inDeck)
                    .append(" 张（主卡组共 ").append(deckLeft).append(" 张）");
            if (inDeck == 0) {
                sb.append("\nP(≥1)=0%：卡组内已无此卡");
            } else {
                sb.append("\n抽 ").append(d).append(" 张：P(≥1)=")
                        .append(percent(hypergeom(deckLeft, inDeck, d, 1)));
                if (inDeck >= 2 && d >= 2) {
                    sb.append("，P(≥2)=").append(percent(hypergeom(deckLeft, inDeck, d, 2)));
                }
            }
            sb.append("\n口径：剩余张数 = 清单张数 − 公开区可见；未计入把除外/墓地卡放回卡组的效果\n");
            return sb.toString();
        });
    }

    @Tool(name = MATERIAL_MATH, value = "核对召唤/特召素材的等级与数量（只算数，不判效果合法性）："
            + "goal=tribute（上级召唤祭品是否够）/synchro（材料等级合计 vs 目标等级）/"
            + "xyz（材料等级是否都等于阶级）/link（材料数 vs 连接值）/fusion（只数数量）。"
            + "materials 传素材句柄列表（逗号分隔，我方场上怪兽或手牌）。")
    public String materialMath(
            @P("goal：tribute / synchro / xyz / link / fusion") String goal,
            @P(value = "目标等级/阶级/连接值；tribute 与 fusion 可省略", required = false)
            Integer targetLevel,
            @P(value = "素材句柄列表（逗号/顿号分隔）；tribute 可省略", required = false)
            String materials) {
        return traced(MATERIAL_MATH, Map.of("goal", safe(goal), "targetLevel", String.valueOf(targetLevel),
                "materials", safe(materials)), () -> {
            DuelState state = snapshot();
            if (state == null) {
                return NO_SNAPSHOT;
            }
            Goal g = goalOf(goal);
            if (g == null) {
                return "goal 只接受 tribute / synchro / xyz / link / fusion";
            }
            FieldCardList materialList = resolveMaterials(materials);
            if (materialList.error() != null) {
                return materialList.error();
            }
            StringBuilder sb = new StringBuilder(header(state)).append("\n素材 ").append(materialList.size())
                    .append(" 只：");
            if (materialList.size() == 0 && g != Goal.TRIBUTE) {
                return "（缺素材句柄：materials 传卡名/格子编码列表，我方场上怪兽或手牌）";
            }
            List<Integer> levels = new ArrayList<>();
            for (FieldCard m : materialList.cards()) {
                Integer lv = m.fact() == null ? null : m.fact().level();
                levels.add(lv);
                sb.append(m.zoneTag()).append(" ").append(m.describe()).append("（等级 ")
                        .append(lv == null ? "?" : lv).append("）；");
            }
            switch (g) {
                case TRIBUTE -> {
                    if (targetLevel == null) {
                        return sb.append("\n需要目标怪兽等级（Lv5-6 需 1 祭品，Lv7+ 需 2）").append("\n").toString();
                    }
                    int required = targetLevel >= 7 ? 2 : targetLevel >= 5 ? 1 : 0;
                    int available = state.monsters(PlayerSide.SELF).size();
                    sb.append("\n目标等级 ").append(targetLevel).append(" → 需祭品 ").append(required)
                            .append("；场上怪兽 ").append(available).append(" 只 → ")
                            .append(available >= required ? "够" : "不够").append("\n");
                }
                case SYNCHRO -> {
                    if (targetLevel == null) {
                        return sb.append("\n需要目标同步怪兽等级").append("\n").toString();
                    }
                    if (levels.stream().anyMatch(l -> l == null)) {
                        sb.append("\n有素材等级未知，等级合计不可核验");
                    } else {
                        int sum = levels.stream().mapToInt(Integer::intValue).sum();
                        sb.append("\n材料等级合计 ").append(sum).append(" vs 目标等级 ").append(targetLevel)
                                .append(" → ").append(sum == targetLevel ? "相等" : "不相等");
                    }
                    sb.append("；调整（Tuner）标记卡库无此字段，不可核验\n");
                }
                case XYZ -> {
                    if (targetLevel == null) {
                        return sb.append("\n需要目标超量怪兽阶级").append("\n").toString();
                    }
                    long match = levels.stream().filter(l -> l != null && l.intValue() == targetLevel).count();
                    long known = levels.stream().filter(l -> l != null).count();
                    sb.append("\n材料等级应全等于阶级 ").append(targetLevel).append("：相符 ")
                            .append(match).append("/").append(materialList.size())
                            .append("（可核验 ").append(known).append(" 只）")
                            .append("；所需素材只数由具体卡面决定，本工具不判\n");
                }
                case LINK -> {
                    if (targetLevel == null) {
                        return sb.append("\n需要目标连接值").append("\n").toString();
                    }
                    sb.append("\n材料 ").append(materialList.size()).append(" 只 vs 连接值 ").append(targetLevel)
                            .append("（一般口径数量足够即可；「连接怪兽可当多份素材」的规则未计入）\n");
                }
                case FUSION -> sb.append("\n融合素材须按卡面名称逐张核对，本工具只数数量：")
                        .append(materialList.size()).append(" 只\n");
            }
            return sb.toString();
        });
    }

    // ---------------------------------------------------------------- 计算

    /** 卡号直接用；卡名经卡库检索，唯一命中才可用（0 = 解析失败）。 */
    private long resolvePasscode(String card) {
        if (card == null || card.isBlank()) {
            return 0;
        }
        String query = card.strip();
        if (query.matches("\\d{1,10}")) {
            long passcode = Long.parseLong(query);
            return facts.byPasscode(passcode).isPresent() ? passcode : 0;
        }
        List<CardFact> matches = facts.searchByName(query, 2);
        return matches.size() == 1 ? matches.getFirst().passcode() : 0;
    }

    private static boolean inMonsterZone(CardInstance card) {
        var kind = card.zone().kind();
        return kind == ZoneKind.MAIN_MONSTER_ZONE || kind == ZoneKind.EXTRA_MONSTER_ZONE;
    }

    /**
     * 超几何 P(X≥k)：X ~ Hypergeom(总体 D, 命中 c, 抽取 d)。
     * 逐项 C(c,j)C(D-c,d-j)/C(D,d) 用 long 精确算（Vandermonde 保证每项 ≤ C(D,d) ≤ 2^D，不溢出）。
     */
    static double hypergeom(int total, int hits, int draws, int atLeast) {
        if (atLeast > Math.min(hits, draws)) {
            return 0.0;
        }
        long denom = comb(total, Math.min(draws, total - draws));
        double p = 0.0;
        for (int j = atLeast; j <= Math.min(hits, draws); j++) {
            if (draws - j > total - hits) {
                continue;
            }
            p += (double) (comb(hits, j) * comb(total - hits, draws - j)) / denom;
        }
        return Math.min(1.0, p);
    }

    static long comb(int n, int k) {
        if (k < 0 || k > n || n < 0) {
            return 0;
        }
        k = Math.min(k, n - k);
        long result = 1;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }

    private static String percent(double p) {
        return String.format(Locale.ROOT, "%.1f%%", p * 100);
    }

    private int countVisible(DuelState state, long passcode) {
        int count = 0;
        for (CardInstance c : state.player(PlayerSide.SELF).hand()) {
            if (!c.isUnknown() && c.passcode() == passcode) {
                count++;
            }
        }
        count += countField(state, PlayerSide.SELF, passcode);
        count += countField(state, PlayerSide.OPPONENT, passcode);
        for (var identity : state.player(PlayerSide.SELF).graveyard()) {
            if (!identity.isUnknown() && identity.passcode() == passcode) {
                count++;
            }
        }
        for (var identity : state.player(PlayerSide.SELF).banished()) {
            if (!identity.isUnknown() && identity.passcode() == passcode) {
                count++;
            }
        }
        return count;
    }

    private int countField(DuelState state, PlayerSide side, long passcode) {
        int count = 0;
        for (CardInstance c : state.monsters(side)) {
            if (!c.isUnknown() && c.passcode() == passcode) {
                count++;
            }
        }
        for (CardInstance c : state.spellTraps(side)) {
            if (!c.isUnknown() && c.passcode() == passcode) {
                count++;
            }
        }
        var field = state.fieldSpell(side);
        if (field.isPresent() && !field.get().isUnknown() && field.get().passcode() == passcode) {
            count++;
        }
        return count;
    }

    private static boolean isExtraDeckType(String cardType) {
        String lower = cardType.toLowerCase(Locale.ROOT);
        return lower.contains("连接") || lower.contains("融合") || lower.contains("同步")
                || lower.contains("超量") || lower.contains("link") || lower.contains("fusion")
                || lower.contains("synchro") || lower.contains("xyz");
    }

    // ---------------------------------------------------------------- 句柄解析

    /** 场上一张卡 + 其卡库事实（fact 可为 null：卡库无资料）。 */
    private record FieldCard(CardInstance instance, PlayerSide side, CardFact fact, String zoneTag) {

        String describe() {
            String name = fact == null ? "卡(" + instance.passcode() + ")" : fact.name();
            String pos = instance.position() == null ? ""
                    : instance.position() == Position.FACE_UP_ATTACK ? "[攻]"
                    : instance.position().isFaceUp() ? "[守]" : "[里侧]";
            return name + pos;
        }
    }

    /** 解析结果：error 非空 = fail-closed 文案（candidates 是歧义/未找到时的可用句柄提示）。 */
    private record Lookup(FieldCard card, String error) {

        static Lookup fail(String message) {
            return new Lookup(null, message);
        }

        static Lookup of(FieldCard card) {
            return new Lookup(card, null);
        }
    }

    private record FieldCardList(List<FieldCard> cards, String error) {

        int size() {
            return cards.size();
        }
    }

    private FieldCardList resolveMaterials(String handles) {
        if (handles == null || handles.isBlank()) {
            return new FieldCardList(List.of(), null);
        }
        List<FieldCard> out = new ArrayList<>();
        for (String handle : handles.split("[,，、;；\\s]+")) {
            if (handle.isBlank()) {
                continue;
            }
            Lookup lookup = resolveOnSide(PlayerSide.SELF, handle, true);
            if (lookup.error() != null) {
                return new FieldCardList(List.of(), lookup.error());
            }
            out.add(lookup.card());
        }
        return new FieldCardList(List.copyOf(out), null);
    }

    /** 解析一只怪兽句柄（场上怪兽区，含手牌可选）。 */
    private Lookup resolveMonsters(PlayerSide side, String handle) {
        if (handle == null || handle.isBlank()) {
            return Lookup.fail("（缺攻击方句柄：给卡名或格子编码如 MMZ-3）");
        }
        return resolveOnSide(side, handle.strip(), false);
    }

    /**
     * 句柄 → 场上卡：格子编码（MMZ-3，复用 ZoneCodes 口径）或卡名（精确优先，其次包含，
     * 英文忽略大小写）。歧义/未找到 fail-closed，附可用句柄清单。
     */
    private Lookup resolveOnSide(PlayerSide side, String handle, boolean includeHand) {
        DuelState state = snapshot();
        if (state == null) {
            return Lookup.fail(NO_SNAPSHOT);
        }
        List<FieldCard> pool = fieldCards(state, side, includeHand);
        var zone = ZoneCodes.parse(handle, side);
        if (zone.isPresent()) {
            ZoneRef ref = zone.get();
            if (ref.owner() != side) {
                return Lookup.fail("（" + handle + " 属于" + labelOf(ref.owner()) + "，"
                        + "与 side 参数矛盾；改 side 或换句柄）");
            }
            CardInstance found = state.cardAt(ref).orElse(null);
            if (found == null) {
                return Lookup.fail("（" + handle + " 是空格）\n可用句柄：\n" + handlesOf(pool));
            }
            return Lookup.of(new FieldCard(found, side, facts.byPasscode(found.passcode()).orElse(null),
                    ZoneCodes.code(ref)));
        }
        String query = handle.strip();
        List<FieldCard> exact = new ArrayList<>();
        List<FieldCard> partial = new ArrayList<>();
        for (FieldCard c : pool) {
            CardFact fact = c.fact();
            if (fact == null) {
                continue;
            }
            if (fact.name().equals(query) || String.valueOf(fact.passcode()).equals(query)
                    || (fact.nameEn() != null && fact.nameEn().equalsIgnoreCase(query))) {
                exact.add(c);
            } else if (fact.name().contains(query)
                    || (fact.nameEn() != null
                    && fact.nameEn().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))) {
                partial.add(c);
            }
        }
        List<FieldCard> matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) {
            return Lookup.fail("（没找到「" + query + "」）\n可用句柄：\n" + handlesOf(pool));
        }
        if (matches.size() > 1) {
            return Lookup.fail("（「" + query + "」匹配到 " + matches.size() + " 张，"
                    + "请用格子编码指定）\n" + handlesOf(matches));
        }
        return Lookup.of(matches.getFirst());
    }

    private List<FieldCard> fieldCards(DuelState state, PlayerSide side, boolean includeHand) {
        List<FieldCard> pool = new ArrayList<>();
        for (CardInstance c : state.monsters(side)) {
            pool.add(new FieldCard(c, side, facts.byPasscode(c.passcode()).orElse(null),
                    ZoneCodes.code(c.zone())));
        }
        for (CardInstance c : state.spellTraps(side)) {
            pool.add(new FieldCard(c, side, facts.byPasscode(c.passcode()).orElse(null),
                    ZoneCodes.code(c.zone())));
        }
        state.fieldSpell(side).ifPresent(c -> pool.add(new FieldCard(c, side,
                facts.byPasscode(c.passcode()).orElse(null), ZoneCodes.code(c.zone()))));
        if (includeHand) {
            for (CardInstance c : state.player(side).hand()) {
                if (!c.isUnknown()) {
                    pool.add(new FieldCard(c, side, facts.byPasscode(c.passcode()).orElse(null),
                            "手牌"));
                }
            }
        }
        return pool;
    }

    private static String handlesOf(List<FieldCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (FieldCard c : cards) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(c.zoneTag()).append(" ").append(c.describe());
        }
        return sb.length() == 0 ? "（该侧场上没有已知卡）" : sb.toString();
    }

    private static String labelOf(PlayerSide side) {
        return side == PlayerSide.SELF ? "我方" : "对方";
    }

    // ---------------------------------------------------------------- 投影

    private FieldCardList pickAttackers(DuelState state, PlayerSide side, String handles,
                                        List<AttackLine> table) {
        if (handles == null || handles.isBlank()) {
            // 默认全体攻击位怪兽，按格序；只保留表里有结算行的（卡库无 ATK 数据的算不了）
            Set<Long> withLines = new LinkedHashSet<>();
            for (AttackLine line : table) {
                withLines.add(line.attackerInstanceId());
            }
            List<FieldCard> out = new ArrayList<>();
            for (CardInstance m : state.monsters(side)) {
                if (m.position() == Position.FACE_UP_ATTACK && withLines.contains(m.instanceId())) {
                    out.add(new FieldCard(m, side, facts.byPasscode(m.passcode()).orElse(null),
                            ZoneCodes.code(m.zone())));
                }
            }
            return out.isEmpty()
                    ? new FieldCardList(List.of(), "（攻击位没有可结算的怪兽）")
                    : new FieldCardList(out, null);
        }
        FieldCardList resolved = resolveMaterials(handles);
        if (resolved.error() != null) {
            return resolved;
        }
        for (FieldCard card : resolved.cards()) {
            if (card.instance().position() != Position.FACE_UP_ATTACK) {
                return new FieldCardList(List.of(),
                        card.describe() + "：不在攻击表示，不能作为攻击方");
            }
        }
        return resolved;
    }

    /** 该攻击者的择优行：仅看还存活的目标；无确定伤害时保留未解明/最差行如实报告。 */
    private AttackLine bestLine(FieldCard attacker, List<AttackLine> table, Set<Long> remaining) {
        long id = attacker.instance().instanceId();
        AttackLine best = null;
        for (AttackLine line : table) {
            if (line.attackerInstanceId() != id) {
                continue;
            }
            if (line.targetInstanceId() == null) {
                // 直接攻击行：防守方全场为空时表里才有，直接可用
                if (remaining.isEmpty()) {
                    return line;
                }
                continue;
            }
            if (!remaining.contains(line.targetInstanceId())) {
                continue;
            }
            if (best == null || line.damageToOpponent() > best.damageToOpponent()) {
                best = line;
            }
        }
        if (best != null) {
            return best;
        }
        // 只剩里侧/未知目标：如实给未解明行（有就给）
        for (AttackLine line : table) {
            if (line.attackerInstanceId() == id && line.outcome() == AttackLine.Outcome.UNRESOLVED) {
                return line;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 公共

    private DuelState snapshot() {
        return snapshot;
    }

    private static PlayerSide sideOf(String side) {
        String s = side == null ? "" : side.strip().toLowerCase(Locale.ROOT);
        return s.contains("opp") || s.contains("对") ? PlayerSide.OPPONENT : PlayerSide.SELF;
    }

    private static PlayerSide opposite(PlayerSide side) {
        return side == PlayerSide.SELF ? PlayerSide.OPPONENT : PlayerSide.SELF;
    }

    private enum Goal {
        TRIBUTE, SYNCHRO, XYZ, LINK, FUSION;

        static Goal of(String text) {
            return switch (text) {
                case "tribute", "祭品", "上级召唤" -> TRIBUTE;
                case "synchro", "同步" -> SYNCHRO;
                case "xyz", "超量" -> XYZ;
                case "link", "连接" -> LINK;
                case "fusion", "融合" -> FUSION;
                default -> null;
            };
        }
    }

    private static Goal goalOf(String goal) {
        return goal == null ? null : Goal.of(goal.strip().toLowerCase(Locale.ROOT));
    }

    private String header(DuelState state) {
        return "[局面快照 第" + state.turnNumber() + "回合 "
                + (state.isMyTurn() ? "我方回合" : "对方回合")
                + " 阶段 " + state.phase() + "]";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 工具调用轨迹（工具名/参数/耗时；写入失败不影响计算与决策）。 */
    private String traced(String tool, Map<String, String> args, Supplier<String> body) {
        long start = System.nanoTime();
        String result;
        try {
            result = body.get();
        } catch (RuntimeException e) {
            log.warn("数学工具 {} 执行异常: {}", tool, e.toString());
            result = "（计算失败：" + e.getClass().getSimpleName() + "）";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(args);
            payload.put("tool", tool);
            payload.put("ms", (System.nanoTime() - start) / 1_000_000);
            traceWriter.event("MATH_TOOL", payload);
        } catch (RuntimeException ignored) {
            // 轨迹写入不得影响决策
        }
        return result;
    }
}
