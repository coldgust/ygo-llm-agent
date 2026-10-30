package com.github.coldgust.ygoagent.decision.planexec.planner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygoagent.decision.plan.ActionSpec;
import com.github.coldgust.ygoagent.decision.plan.PlanStep;
import com.github.coldgust.ygoagent.decision.plan.ZoneCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 规划器 JSON → TurnPlan 的确定性解析（v6 §3.1 输出 schema）。
 *
 * <p>解析策略：<b>外层宽容、内层严格</b>。
 * <ul>
 *   <li>外层宽容：模型常带 ```json 围栏、前后寒暄、中文动词、字符串卡号、甚至 JSON 被截断，
 *       这些都先做确定性修复再解析——宁可修，不要因为格式噪音丢掉整份计划。</li>
 *   <li>内层严格：动作词必须落在封闭词表里（未知动词 = 不按 schema 说话，整份拒绝）。
 *       卡号则宽松：卡库里查不到只打 {@code unknownPasscode} 标（卡库可能滞后于新卡），
 *       <b>"没写卡号"更不算非法</b>——计划常引用
 *       "先检索再通召"这种当前还看不见的卡，逼它写卡号只会逼出编造；
 *       {@code passcode=0} 在接地层是通配符，接不上地会触发"计划作废 + 重规划"，
 *       比整份丢掉温和得多（实机一局因此整回合没有计划）。</li>
 * </ul>
 */
public final class PlanTreeParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static final int MAX_STEPS = 6;
    public static final int MAX_BRANCHES = 4;
    private static final int MAX_BRANCH_STEPS = 3;

    /** 允许的动作词（封闭词表，v6 §3.1）。 */
    private static final Set<String> VERBS = Set.of(
            "activate", "summon", "specialsummon", "setmonster", "setspelltrap",
            "attack", "place", "select", "reposition", "link",
            "end_turn", "enter_battle", "enter_main2", "end_battle");

    /** 不需要卡号的动作（选择类/流程类）。 */
    private static final Set<String> NO_PASSCODE = Set.of(
            "place", "select", "end_turn", "enter_battle", "enter_main2", "end_battle");

    /**
     * 可以省略卡号的动作：目标常在<b>额外卡组</b>，而锚点里看不到额外卡组内容，
     * 要求模型给出卡号等于逼它编造。{@code passcode=0} 在 {"@link PlanGrounder#matchSpec"}
     * 里是通配符（只按动词接地）。
     */
    private static final Set<String> WILDCARD_PASSCODE = Set.of("link", "specialsummon");

    /** 别名 → 规范动词（键已归一：小写、去掉空格/下划线/连字符/点）。 */
    private static final Map<String, String> VERB_ALIASES = buildVerbAliases();

    /** 别名 → 规范阶段（键已归一）。 */
    private static final Map<String, String> WHEN_ALIASES = buildWhenAliases();

    public record ParsedPlan(String thinking, String goal, List<PlanStep> steps,
                             List<TurnPlan.TurnBranch> branches, double confidence,
                             int droppedSteps) {
    }

    /**
     * 解析结果：{@code plan == null} 时 {@code failure} 说明原因（用于日志与轨迹定位）。
     * <p>句柄而不是异常：解析失败是常态，不该用异常流控。
     */
    public record Result(ParsedPlan plan, String failure) {
    }

    /**
     * 顾问模式（{@code PLANNER_PATH_PLANNING=false}）的产出不带步骤，只有
     * {@code analysis / goal / tactics / combo} 四段自然语言。这里把它们拼成一段
     * {@code thinking}，让下游（骨架计划渲染、轨迹、复盘）无需区分两种形态。
     *
     * <p>字段缺省时原样返回，因此带步骤的旧格式完全不受影响。
     */
    private static String composeAdvisory(JsonNode root, String base) {
        String analysis = text(root, "analysis", "situation", "board_analysis");
        String resources = text(root, "resources", "resource", "resource_check");
        String tactics = text(root, "tactics", "strategy", "approach");
        String combo = text(root, "combo", "combos", "combo_plan");
        String options = renderOptions(child(root, "options", "plans", "candidates"));
        if (analysis == null && tactics == null && combo == null && options == null) {
            return base;
        }
        StringBuilder sb = new StringBuilder();
        if (base != null && !base.isBlank()) {
            sb.append(base.trim()).append('\n');
        }
        if (analysis != null) {
            sb.append("场面：").append(analysis.trim()).append('\n');
        }
        if (resources != null) {
            sb.append("资源：").append(resources.trim()).append('\n');
        }
        if (options != null) {
            sb.append("方案比较：\n").append(options);
        }
        if (tactics != null) {
            sb.append("战术：").append(tactics.trim()).append('\n');
        }
        if (combo != null) {
            sb.append("combo：").append(combo.trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 取一段文本；如果给的是<b>动作序列数组</b>（{@code ["A","B","C"]} 或 {@code [{"do":"A"},…]}），
     * 就按 {@code A → B → C} 连起来。
     *
     * <p>为什么要支持数组：规划器被要求"把整条线写成动作序列"后，最自然的输出就是数组；
     * 只认字符串会让整条线被丢弃（渲染成空行），计划看起来又变成"只有一句话"。
     */
    private static String sequence(JsonNode item, String... keys) {
        for (String key : keys) {
            JsonNode node = item.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode element : node) {
                    String part = element.isTextual()
                            ? element.asText()
                            : (element.isObject() ? text(element, "do", "action", "step", "line") : null);
                    if (part != null && !part.isBlank()) {
                        parts.add(part.trim());
                    }
                }
                if (!parts.isEmpty()) {
                    return String.join(" → ", parts);
                }
                continue;
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText().trim();
            }
        }
        return null;
    }

    /**
     * 渲染顾问模式的候选方案比较：{@code [{line,gain,risk,pick,why}]}。
     *
     * <p>这一步是"最优打法"的来源：不列候选、只给结论，等于让执行器相信直觉。
     * 渲染时把 pick 标出来，让下游（执行器/复盘）一眼看到选了哪条、为什么淘汰别的。
     */
    private static String renderOptions(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (JsonNode item : node) {
            if (++i > 5) {
                break;
            }
            if (item.isTextual()) {
                sb.append("  ").append(indexMark(i)).append(' ').append(item.asText().trim()).append('\n');
                continue;
            }
            // line 允许是动作序列数组：["交闪检索大黄蜂","大黄蜂出衍生物","衍生物连接召唤雫空"]
            String line = sequence(item, "line", "steps", "plan", "what");
            String gain = text(item, "gain", "benefit", "upside");
            String risk = text(item, "risk", "cost", "downside");
            String why = text(item, "why", "reason", "verdict");
            boolean pick = item.path("pick").asBoolean(false);
            if (line == null && gain == null && risk == null && why == null) {
                continue;
            }
            sb.append("  ").append(indexMark(i)).append(pick ? "[选] " : "[否] ");
            if (line != null) {
                sb.append(line.trim());
            }
            if (gain != null) {
                sb.append(" ｜ 收益：").append(gain.trim());
            }
            if (risk != null) {
                sb.append(" ｜ 风险：").append(risk.trim());
            }
            if (why != null) {
                sb.append(" ｜ ").append(why.trim());
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

    public static ParsedPlan parse(String json) {
        return parseDetailed(json).plan();
    }

    public static Result parseDetailed(String json) {
        return parseDetailed(json, null);
    }

    /**
     * @param knownCard 卡号是否存在于卡库；null = 不校验（单测/无卡库场景）。
     *                  <p>只用来识别"编造卡号"，不用来判断"能不能发动"。
     */
    public static Result parseDetailed(String json, LongPredicate knownCard) {
        String candidate = extractJson(json);
        if (candidate == null) {
            return new Result(null, "没有可用的 JSON 对象（空输出或非 JSON）");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(candidate);
        } catch (Exception e) {
            return new Result(null, "JSON 解析失败: " + e.getClass().getSimpleName());
        }
        if (node == null || !node.isObject()) {
            return new Result(null, "顶层不是对象");
        }
        // 允许 {"plan": {...}} / {"data": {...}} 这类外壳
        JsonNode plan = child(node, "plan", "data", "result");
        JsonNode root = plan != null && plan.isObject() ? plan : node;

        JsonNode mainLine = child(root, "main_line", "mainline", "mainLine", "main");
        if (mainLine == null || !mainLine.isObject()) {
            mainLine = root; // 容错：允许直接给 steps
        }
        String thinking = composeAdvisory(root, text(root, "thinking", "thought", "reasoning"));
        String goal = firstNonBlank(text(mainLine, "goal"), text(root, "goal"));
        double confidence = clamp01(decimal(root, 0.5, "confidence"));

        List<PlanStep> steps = new ArrayList<>();
        List<JsonNode> stepNodes = list(child(mainLine, "steps", "step"));
        for (int i = 0; i < stepNodes.size(); i++) {
            StepOutcome outcome = buildStep(i, stepNodes.get(i), false, knownCard);
            if (outcome.step() == null) {
                // 契约破坏（未知动词/缺卡号/空步骤）→ 整份计划不可信，交回上层走 one-shot
                return new Result(null, "第 " + (i + 1) + " 步非法：" + outcome.problem());
            }
            steps.add(outcome.step());
            if (steps.size() >= MAX_STEPS) {
                break;
            }
        }

        int[] dropped = {0};
        List<TurnPlan.TurnBranch> branches = new ArrayList<>();
        for (JsonNode branchNode : list(child(mainLine, "branches", "branch", "contingencies"))) {
            if (branches.size() >= MAX_BRANCHES) {
                break;
            }
            TurnPlan.TurnBranch branch = parseBranch(branchNode, dropped, knownCard);
            if (branch != null) {
                branches.add(branch);
            }
        }
        if (steps.isEmpty() && branches.isEmpty()) {
            // 显式的"本回合按住不动"是合法计划（对方回合很常见，prompt 也允许 steps/branches 皆空）。
            // 但要能区分两种情况：模型按 schema 给了空数组 vs 压根没按 schema 输出。
            // 顾问模式天然没有 steps/branches，只要有 analysis/tactics/combo 就算按 schema 输出
            boolean advisory = child(root, "analysis", "situation", "board_analysis") != null
                    || child(root, "tactics", "strategy", "approach") != null
                    || child(root, "combo", "combos", "combo_plan") != null
                    || child(root, "options", "plans", "candidates") != null;
            boolean declared = child(mainLine, "steps", "step") != null
                    || child(mainLine, "branches", "branch", "contingencies") != null;
            if (!declared && !advisory) {
                return new Result(null, "既无 steps 也无 branches 字段（未按 schema 输出）");
            }
        }
        return new Result(new ParsedPlan(firstNonBlank(thinking, ""), firstNonBlank(goal, ""),
                steps, branches, confidence, dropped[0]), null);
    }

    /* ================= 结构解析 ================= */

    /** 分支也转成 step（供 ③ 相似匹配接地）。分支步骤允许缺卡号（只是提示性假设）。 */
    private static TurnPlan.TurnBranch parseBranch(JsonNode branchNode, int[] dropped,
                                                   LongPredicate knownCard) {
        if (branchNode == null) {
            return null;
        }
        JsonNode assumesNode = child(branchNode, "if", "assumes", "when");
        Map<String, Object> assumes = new LinkedHashMap<>();
        if (assumesNode != null && assumesNode.isTextual()) {
            assumes.put("description", assumesNode.asText());
        } else if (assumesNode != null && assumesNode.isObject()) {
            assumesNode.properties().forEach(entry -> assumes.put(entry.getKey(), entry.getValue().asText()));
        }
        List<PlanStep> steps = new ArrayList<>();
        int index = 100; // 分支步骤 index 从 100 起，与主计划区分
        for (JsonNode stepNode : list(child(branchNode, "steps", "step"))) {
            StepOutcome outcome = buildStep(index, stepNode, true, knownCard);
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
        String name = firstNonBlank(text(branchNode, "name"),
                String.valueOf(assumes.getOrDefault("description", "分支")));
        return new TurnPlan.TurnBranch(name, assumes, steps);
    }

    /** 单步构建结果：成功给 step，失败给人类可读原因。 */
    private record StepOutcome(PlanStep step, String problem) {
    }

    private static StepOutcome buildStep(int index, JsonNode stepNode, boolean lenientPasscode,
                                        LongPredicate knownCard) {
        if (stepNode == null || stepNode.isNull()) {
            return new StepOutcome(null, "空步骤");
        }
        if (stepNode.isTextual()) {
            stepNode = MAPPER.createObjectNode().put("verb", stepNode.asText()); // 极简写法："activate"
        }
        String rawVerb = text(stepNode, "verb", "action", "act", "type");
        String verb = canonicalVerb(rawVerb);
        if (verb == null) {
            return new StepOutcome(null, "未知动词「" + (rawVerb == null ? "缺失" : rawVerb) + "」");
        }
        long passcode = number(stepNode, "passcode", "card", "cardId", "card_id", "code", "id");
        // 位置是决策点：召唤/盖放/连接召唤/放置都可以（也应该）指明放到哪一格。
        // 解析失败只丢位置、不丢整步——位置写错（"中间那格"）远比动作写错轻。
        JsonNode zoneNode = child(stepNode, "zone", "place_to", "dest", "destination", "area", "zoneCode");
        ZoneRef zone = parseZone(zoneNode);
        // 缺卡号（passcode=0）一律放行：它常是"稍后检索到手的卡"，接地层按动词通配；
        // 硬拒的代价是整份计划作废（实机一局因此整回合没有计划）。
        boolean wildcard = passcode <= 0 && !NO_PASSCODE.contains(verb)
                && !WILDCARD_PASSCODE.contains(verb) && !lenientPasscode;
        // 卡号给了但卡库里没有：**只打标，不拒绝**。卡库（15k 张）可能滞后于新卡，
        // 把"卡库里查不到"当成编造会误杀真卡；接地时对不上自然会触发"计划作废+重规划"。
        boolean unknownCard = passcode > 0 && knownCard != null && !knownCard.test(passcode);
        String when = canonicalWhen(text(stepNode, "when", "phase", "timing", "at"));
        String why = firstNonBlank(text(stepNode, "why", "reason", "note"), "");
        RequestKind kind = verb.equals("attack") ? RequestKind.BATTLE_PHASE
                : TurnPlan.requestKindOf(verb.equals("place") && text(stepNode, "when", "phase", "timing", "at") == null
                        ? "PLACE" : when);
        Map<String, Object> effects = new LinkedHashMap<>();
        if (!why.isBlank()) {
            effects.put("why", why);
        }
        if (zone != null) {
            effects.put("zoneCode", ZoneCodes.code(zone));
        } else if (zoneNode != null && !zoneNode.isNull()) {
            // 写了位置但读不懂：打标，便于复盘"模型是不是不会写 zone"
            effects.put("zoneUnparsed", String.valueOf(zoneNode.asText("")));
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
     * 计划步的位置字段 → 区域。接受 {@code "MMZ-3"}、{@code "我方主怪兽区#3"}，
     * 也接受对象 {@code {"owner":"self","kind":"mainMonster","sequence":3}}。
     * 读不懂 → {@link Optional#empty()}（上层只丢位置，不丢这一步）。
     */
    static ZoneRef parseZone(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            return ZoneCodes.parse(node.asText(""), PlayerSide.SELF).orElse(null);
        }
        if (!node.isObject()) {
            return null;
        }
        String owner = text(node, "owner", "side", "player");
        PlayerSide side = owner != null && (owner.toUpperCase(Locale.ROOT).contains("OPP")
                || owner.contains("对方") || owner.contains("对手"))
                ? PlayerSide.OPPONENT : PlayerSide.SELF;
        String kind = text(node, "kind", "type", "zone", "name");
        int seq = (int) number(node, "sequence", "seq", "index", "no", "number");
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

    /* ================= 词表归一 ================= */

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
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (WHEN_ALIASES.containsKey(normalizeKey(raw))) {
            return WHEN_ALIASES.get(normalizeKey(raw));
        }
        return trimmed;
    }

    /** 归一化：小写 + 去掉空白/下划线/连字符/点/斜杠。 */
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

    /* ================= JSON 提取与修复 ================= */

    /**
     * 从模型输出里取出 JSON 对象：剥 ``` 围栏、丢前后寒暄、按括号配对截断；
     * 若被截断（括号/引号未闭合）则补齐后再试。
     */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw;
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int lineEnd = s.indexOf('\n', fence);
            int close = s.indexOf("```", fence + 3);
            if (lineEnd > 0) {
                s = close > lineEnd ? s.substring(lineEnd + 1, close) : s.substring(lineEnd + 1);
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        String body = s.substring(start);
        int end = matchBrace(body);
        String json = end > 0 ? body.substring(0, end + 1) : repair(body);
        if (json == null) {
            return null;
        }
        // 常见噪音：对象/数组尾逗号
        return json.replaceAll(",\\s*([}\\]])", "$1");
    }

    /** 返回与首个 '{' 配对的 '}' 下标；未闭合返回 -1。 */
    private static int matchBrace(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 截断修复：补未闭合的字符串引号与括号（模型被 max_tokens 截断时很常见）。 */
    private static String repair(String s) {
        StringBuilder sb = new StringBuilder(s);
        boolean inString = false;
        boolean escaped = false;
        Deque<Character> stack = new ArrayDeque<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }
        }
        if (inString) {
            sb.append('"');
        }
        // 截断处可能停在 "key": 或 "key": 值 一半：去掉尾部悬空的逗号/冒号
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || last == ':' || Character.isWhitespace(last)) {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '{' ? '}' : ']');
        }
        String repaired = sb.toString();
        return repaired.equals(s) ? null : repaired;
    }

    /* ================= 小工具 ================= */

    private static JsonNode child(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... keys) {
        JsonNode v = child(node, keys);
        return v == null ? null : v.asText("");
    }

    private static long number(JsonNode node, String... keys) {
        JsonNode v = child(node, keys);
        if (v == null) {
            return 0L;
        }
        if (v.isNumber()) {
            return v.asLong(0);
        }
        try {
            return Long.parseLong(v.asText("").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 小数读取（confidence 这类 0.0–1.0 字段，不能用 asLong）。 */
    private static double decimal(JsonNode node, double fallback, String... keys) {
        JsonNode v = child(node, keys);
        if (v == null) {
            return fallback;
        }
        if (v.isNumber()) {
            return v.asDouble(fallback);
        }
        try {
            return Double.parseDouble(v.asText("").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<JsonNode> list(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        }
        if (node.isObject()) {
            return List.of(node); // 单步写成对象
        }
        return List.of();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private PlanTreeParser() {
    }
}
