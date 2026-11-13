package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.BranchDraft;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.OptionDraft;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.StepDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * 规划器结构化输出 → TurnPlan 的确定性校验与组装（v6 §3.1 输出 schema）。
 * JSON 提取与解析交给框架（AiServices POJO），这里只做语义层：
 * 动词/阶段别名归一、步骤上限、卡号已知性打标、非法分支步丢弃、顾问段拼装。
 */
public final class PlanAssembler {

    public static final int MAX_STEPS = 6;
    public static final int MAX_BRANCHES = 4;
    private static final int MAX_BRANCH_STEPS = 3;

    /** 允许的动作词（封闭词表，v6 §3.1）。 */
    private static final Set<String> VERBS = Set.of(
            "activate", "summon", "specialsummon", "setmonster", "setspelltrap",
            "attack", "place", "select", "reposition", "link",
            "end_turn", "enter_battle", "enter_main2", "end_battle");

    private static final Set<String> NO_PASSCODE = Set.of(
            "place", "select", "end_turn", "enter_battle", "enter_main2", "end_battle");

    /**
     * 可以省略卡号的动作：目标常在<b>额外卡组</b>，而锚点里看不到额外卡组内容，
     * 要求模型给出卡号等于逼它编造。{@code passcode=0} 在 {@code ActionSpecMatcher#matchSpec}
     * 里是通配符（只按动词接地）。
     */
    private static final Set<String> WILDCARD_PASSCODE = Set.of("link", "specialsummon");

    /** 别名 → 规范动词（键已归一：小写、去掉空格/下划线/连字符/点）。 */
    private static final Map<String, String> VERB_ALIASES = buildVerbAliases();

    /** 别名 → 规范阶段（键已归一）。 */
    private static final Map<String, String> WHEN_ALIASES = buildWhenAliases();

    public record Parsed(String thinking, String goal, List<PlanStep> steps,
                         List<TurnPlan.TurnBranch> branches, double confidence,
                         int droppedSteps) {
    }

    /**
     * 组装结果：{@code plan == null} 时 {@code failure} 说明原因（用于日志与轨迹定位）。
     * <p>句柄而不是异常：校验失败是常态，不该用异常流控。
     */
    public record Result(Parsed plan, String failure) {
    }

    public static Result assemble(PlanAnswer envelope, LongPredicate knownCard) {
        PlanAnswer answer = envelope.unwrap();
        String thinking = composeAdvisory(answer);
        String goal = notBlank(answer.goal()) ? answer.goal() : "";
        double confidence = answer.confidenceOr(0.5);

        PlanAnswer.MainLine mainLine = answer.mainLine();
        List<StepDraft> stepDrafts = mainLine == null || mainLine.steps() == null
                ? List.of() : mainLine.steps();
        List<BranchDraft> branchDrafts = mainLine == null || mainLine.branches() == null
                ? List.of() : mainLine.branches();

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < stepDrafts.size() && steps.size() < MAX_STEPS; i++) {
            StepOutcome outcome = buildStep(i, stepDrafts.get(i), false, knownCard);
            if (outcome.step() == null) {
                // 契约破坏（未知动词/缺卡号/空步骤）→ 整份计划不可信，交回上层走 直答
                return new Result(null, "第 " + (i + 1) + " 步非法：" + outcome.problem());
            }
            steps.add(outcome.step());
        }

        int[] dropped = {0};
        List<TurnPlan.TurnBranch> branches = new ArrayList<>();
        for (BranchDraft branchDraft : branchDrafts) {
            if (branches.size() >= MAX_BRANCHES) {
                break;
            }
            TurnPlan.TurnBranch branch = parseBranch(branchDraft, dropped, knownCard);
            if (branch != null) {
                branches.add(branch);
            }
        }
        if (steps.isEmpty() && branches.isEmpty()) {
            // 显式的"本回合按住不动"是合法计划（对方回合很常见，prompt 也允许 steps/branches 皆空）。
            // 但要能区分两种情况：模型按 schema 给了空数组（mainLine != null）vs 压根没按 schema 输出。
            // 顾问模式天然没有 steps/branches，只要有 analysis/tactics/combo/options 就算按 schema 输出
            boolean advisory = answer.analysis() != null || answer.tactics() != null
                    || answer.combo() != null || answer.options() != null;
            if (mainLine == null && !advisory) {
                return new Result(null, "既无 main_line 也无顾问段（未按 schema 输出）");
            }
        }
        return new Result(new Parsed(firstNonBlank(thinking, ""), goal,
                steps, branches, confidence, dropped[0]), null);
    }

    /**
     * 顾问模式的产出不带步骤，只有 analysis / resources / tactics / combo（+ options 比较）。
     * 这里把它们拼成一段 thinking，让下游（骨架计划渲染、轨迹、复盘）无需区分两种形态。
     */
    private static String composeAdvisory(PlanAnswer answer) {
        String base = notBlank(answer.thinking()) ? answer.thinking() : "";
        boolean advisory = answer.analysis() != null || answer.resources() != null
                || answer.tactics() != null || answer.combo() != null || answer.options() != null;
        if (!advisory) {
            return base;
        }
        StringBuilder sb = new StringBuilder();
        if (!base.isBlank()) {
            sb.append(base.trim()).append('\n');
        }
        if (notBlank(answer.analysis())) {
            sb.append("场面：").append(answer.analysis().trim()).append('\n');
        }
        if (notBlank(answer.resources())) {
            sb.append("资源：").append(answer.resources().trim()).append('\n');
        }
        String options = renderOptions(answer.options());
        if (options != null) {
            sb.append("方案比较：\n").append(options);
        }
        if (notBlank(answer.tactics())) {
            sb.append("战术：").append(answer.tactics().trim()).append('\n');
        }
        if (notBlank(answer.combo())) {
            sb.append("combo：").append(answer.combo().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 渲染顾问模式的候选方案比较（这一步是"最优打法"的来源）：
     * 把 pick 标出来，让下游（执行器/复盘）一眼看到选了哪条、为什么淘汰别的。
     */
    private static String renderOptions(List<OptionDraft> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (OptionDraft item : options) {
            if (++i > 5) {
                break;
            }
            String line = item.line() == null || item.line().isEmpty()
                    ? null : String.join(" → ", item.line());
            if (line == null && !notBlank(item.gain()) && !notBlank(item.risk())
                    && !notBlank(item.why())) {
                continue;
            }
            sb.append("  ").append(indexMark(i)).append(Boolean.TRUE.equals(item.pick()) ? "[选] " : "[否] ");
            if (line != null) {
                sb.append(line.trim());
            }
            if (notBlank(item.gain())) {
                sb.append(" ｜ 收益：").append(item.gain().trim());
            }
            if (notBlank(item.risk())) {
                sb.append(" ｜ 风险：").append(item.risk().trim());
            }
            if (notBlank(item.why())) {
                sb.append(" ｜ ").append(item.why().trim());
            }
            sb.append('\n');
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String indexMark(int i) {
        return switch (i) {
            case 1 -> "①";
            case 2 -> "②";
            case 3 -> "③";
            case 4 -> "④";
            default -> "⑤";
        };
    }

    /** 分支也转成 step（供 ③ 相似匹配接地）。分支步骤允许缺卡号（只是提示性假设）。 */
    private static TurnPlan.TurnBranch parseBranch(BranchDraft draft, int[] dropped,
                                                   LongPredicate knownCard) {
        if (draft == null) {
            return null;
        }
        Map<String, Object> assumes = new LinkedHashMap<>();
        if (draft.assumes() != null) {
            draft.assumes().forEach((k, v) -> assumes.put(k, String.valueOf(v)));
        }
        List<PlanStep> steps = new ArrayList<>();
        int index = 100; // 分支步骤 index 从 100 起，与主计划区分
        List<StepDraft> stepDrafts = draft.steps() == null ? List.of() : draft.steps();
        for (StepDraft stepDraft : stepDrafts) {
            StepOutcome outcome = buildStep(index, stepDraft, true, knownCard);
            if (outcome.step() != null) {
                steps.add(outcome.step());
                index++;
            } else {
                dropped[0]++;
            }
            if (steps.size() >= MAX_BRANCH_STEPS) {
                break;
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        String name = firstNonBlank(draft.name(),
                String.valueOf(assumes.getOrDefault("description", "分支")));
        return new TurnPlan.TurnBranch(name, assumes, steps);
    }

    private record StepOutcome(PlanStep step, String problem) {
    }

    private static StepOutcome buildStep(int index, StepDraft draft, boolean lenientPasscode,
                                         LongPredicate knownCard) {
        if (draft == null || (draft.verb() == null && draft.when() == null)) {
            return new StepOutcome(null, "空步骤");
        }
        String verb = canonicalVerb(draft.verb());
        if (verb == null) {
            return new StepOutcome(null, "未知动词「" + (draft.verb() == null ? "缺失" : draft.verb()) + "」");
        }
        long passcode = draft.passcode() == null ? 0 : draft.passcode();
        // 位置是决策点：召唤/盖放/连接召唤/放置都可以（也应该）指明放到哪一格。
        // 解析失败只丢位置、不丢整步——位置写错（"中间那格"）远比动作写错轻。
        ZoneRef zone = parseZone(draft.zone());
        boolean wildcard = passcode <= 0 && !NO_PASSCODE.contains(verb)
                && !WILDCARD_PASSCODE.contains(verb) && !lenientPasscode;
        // 卡号给了但卡库里没有：**只打标，不拒绝**。卡库（15k 张）可能滞后于新卡，
        // 把"卡库里查不到"当成编造会误杀真卡；接地时对不上自然会触发"计划作废+重规划"。
        boolean unknownCard = passcode > 0 && knownCard != null && !knownCard.test(passcode);
        String when = canonicalWhen(draft.when());
        String why = notBlank(draft.why()) ? draft.why() : "";
        RequestKind kind = verb.equals("attack") ? RequestKind.BATTLE_PHASE
                : TurnPlan.requestKindOf(verb.equals("place") && draft.when() == null
                        ? "PLACE" : when);
        Map<String, Object> effects = new LinkedHashMap<>();
        if (!why.isBlank()) {
            effects.put("why", why);
        }
        if (zone != null) {
            effects.put("zoneCode", ZoneCodes.code(zone));
        } else if (draft.zone() != null && !String.valueOf(draft.zone()).isBlank()) {
            // 写了位置但读不懂：打标，便于复盘"模型是不是不会写 zone"
            effects.put("zoneUnparsed", String.valueOf(draft.zone()));
        }
        if (wildcard) {
            effects.put("wildcardPasscode", true);   // 缺卡号：接地只能按动词匹配
        }
        if (unknownCard) {
            effects.put("unknownPasscode", true);     // 卡库里没有该卡：可能是新卡，也可能写错
        }
        return new StepOutcome(new PlanStep(index, kind,
                List.of(new ActionSpec(verb, Math.max(0, passcode), zone)), Map.of(), effects, 0.8), null);
    }

    /**
     * 计划步的位置字段 → 区域。接受 {@code "MMZ-3"}、{@code "我方主怪兽区#3"}（字符串），
     * 也接受对象 {@code {"owner":"self","kind":"mainMonster","sequence":3}}。
     * 读不懂 → null（上层只丢位置，不丢这一步）。
     */
    static ZoneRef parseZone(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof String s) {
            return s.isBlank() ? null : ZoneCodes.parse(s, PlayerSide.SELF).orElse(null);
        }
        if (node instanceof Number n) {
            return ZoneCodes.parse(String.valueOf(n), PlayerSide.SELF).orElse(null);
        }
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        String owner = firstNonBlank(text(map, "owner"), text(map, "side"), text(map, "player"));
        PlayerSide side = owner != null && (owner.toUpperCase(Locale.ROOT).contains("OPP")
                || owner.contains("对方") || owner.contains("对手"))
                ? PlayerSide.OPPONENT : PlayerSide.SELF;
        String kind = firstNonBlank(text(map, "kind"), text(map, "type"), text(map, "zone"), text(map, "name"));
        int seq = number(firstNonBlank(
                map.get("sequence") == null ? null : String.valueOf(map.get("sequence")),
                map.get("seq") == null ? null : String.valueOf(map.get("seq")),
                map.get("index") == null ? null : String.valueOf(map.get("index")),
                map.get("no") == null ? null : String.valueOf(map.get("no")),
                map.get("number") == null ? null : String.valueOf(map.get("number"))));
        if (kind == null) {
            return null;
        }
        // 对象里的 sequence 可能是 0 起也可能 1 起：统一按"kind + 序号字面量"再走一遍文本解析，
        // 这样 "mainMonster" + 2 与 "MMZ-2" 语义一致（都以 1 起算，见 ZoneCodes）。
        String candidateKind = switch (kind.trim().toUpperCase(Locale.ROOT)) {
            case "MAINMONSTER", "MAIN_MONSTER_ZONE", "MMZ", "MZ" -> "MMZ";
            case "EXTRAMONSTER", "EXTRA_MONSTER_ZONE", "EMZ" -> "EMZ";
            case "SPELLTRAP", "SPELL_TRAP_ZONE", "STZ", "SZ" -> "STZ";
            case "PENDULUM", "PENDULUM_ZONE", "PZ" -> "PZ";
            case "FIELD", "FIELD_ZONE", "FZ" -> "FZ";
            default -> kind.trim().toUpperCase(Locale.ROOT);
        };
        String encoded = "FZ".equals(candidateKind)
                ? "FZ" : candidateKind + "-" + (seq <= 0 ? 1 : seq);
        return ZoneCodes.parse(encoded, side).orElse(null);
    }

    private static String text(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int number(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String canonicalVerb(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = normalizeKey(raw);
        String mapped = VERB_ALIASES.get(key);
        if (mapped != null) {
            return mapped;
        }
        return VERBS.contains(key) ? key : null;
    }

    private static String canonicalWhen(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MAIN1";
        }
        String key = normalizeKey(raw);
        if (WHEN_ALIASES.containsKey(key)) {
            return WHEN_ALIASES.get(key);
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeKey(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c) || c == '_' || c == '-' || c == '.' || c == '/') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Map<String, String> buildVerbAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        alias(m, "activate", "activateeffect", "activation", "use", "发动", "使用", "发动效果", "手卡发动");
        alias(m, "summon", "normalsummon", "ns", "召唤", "通常召唤", "上级召唤", "advancesummon", "tributesummon");
        alias(m, "specialsummon", "ss", "spsummon", "特殊召唤", "特召", "特殊召唤怪兽");
        alias(m, "setmonster", "setm", "盖怪", "盖放怪兽", "setdefense", "里侧守备");
        alias(m, "setspelltrap", "setst", "set", "盖卡", "盖放", "盖放魔陷", "设置魔陷", "盖放魔法陷阱", "盖魔法陷阱");
        alias(m, "attack", "攻击", "攻击宣言", "declareattack");
        alias(m, "place", "放置", "选择区域", "selectplace", "选区");
        alias(m, "select", "选择", "选取", "selectcard", "选卡");
        alias(m, "reposition", "poschange", "changeposition", "变更表示形式", "改变表示形式", "转变表示形式");
        alias(m, "link", "linksummon", "链接召唤", "连接召唤");
        alias(m, "end_turn", "endturn", "结束回合", "回合结束", "endphaseaction");
        alias(m, "enter_battle", "enterbattle", "battlephase", "bp", "进战阶", "进入战斗阶段", "战斗阶段");
        alias(m, "enter_main2", "entermain2", "main2", "m2", "进入主要阶段2", "主要阶段2");
        alias(m, "end_battle", "endbattle", "结束战斗阶段", "退出战斗阶段");
        return Map.copyOf(m);
    }

    private static void alias(Map<String, String> m, String canonical, String... names) {
        m.put(normalizeKey(canonical), canonical);
        for (String n : names) {
            m.put(normalizeKey(n), canonical);
        }
    }

    private static Map<String, String> buildWhenAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        aliasWhen(m, "MAIN1", "main", "m1", "mainphase1", "主要阶段1", "主要阶段一", "mp1");
        aliasWhen(m, "MAIN2", "m2", "mainphase2", "主要阶段2", "主要阶段二", "mp2");
        aliasWhen(m, "BATTLE", "bp", "battlephase", "战斗阶段", "战斗阶段开始");
        aliasWhen(m, "EP", "endphase", "结束阶段", "ep.");
        aliasWhen(m, "CHAIN", "chain", "连锁", "连锁响应");
        aliasWhen(m, "PLACE", "place", "selectplace", "放置", "放置区域", "选区", "选择区域");
        aliasWhen(m, "CARD_SELECT", "cardselect", "选卡", "选择卡片");
        return Map.copyOf(m);
    }

    private static void aliasWhen(Map<String, String> m, String canonical, String... names) {
        m.put(normalizeKey(canonical), canonical);
        for (String n : names) {
            m.put(normalizeKey(n), canonical);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 多候选取第一个非空的。 */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private PlanAssembler() {
    }
}
