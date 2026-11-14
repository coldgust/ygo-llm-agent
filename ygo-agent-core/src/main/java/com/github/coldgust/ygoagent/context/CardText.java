package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

public final class CardText {

    /**
     * 一次"发动"的性质：发动了<b>卡本身</b>（永续魔陷/场地魔法翻开上场的发动），
     * 还是发动了某条<b>效果</b>，或无法判定（见 {@link #activationKind}）。
     */
    public enum ActivationKind { CARD, EFFECT, UNKNOWN }

    /** 条款标记（多效果卡）。①②③… 是印刷顺序，和引擎的"第 N 个可发动效果"不是一回事。 */
    private static final String CLAUSE_MARKS = "①②③④⑤⑥⑦⑧";

    /** 压缩展示的单条条款长度：只用于持久段（记忆记录行）——快照一次性段一律全文。 */
    public static final int BRIEF_LIMIT = 140;
    public static final int ALL_TOTAL_LIMIT = 400;

    private static final String MISSING_CARD = "（卡库无这张卡的资料，不要凭印象编造效果）";

    private final CardFacts facts;

    public CardText(CardFacts facts) {
        this.facts = facts;
    }


    /**
     * 卡的身份行：{@code 闪刀姬-零衣(26077387)〔效果／怪兽｜ATK1500/DEF1500〕}。
     *
     * <p>种类与攻守紧跟名字，是因为"盖得下去吗/能不能当阻抗/会不会被战破"全靠这几个字。
     */
    public String card(long passcode) {
        if (passcode <= 0) {
            return "未知卡";
        }
        return facts.byPasscode(passcode)
                .map(this::cardLine)
                .orElse("卡(" + passcode + ")" + MISSING_CARD);
    }

    /**
     * 这次发动的是哪一条条款（全文）：描述码可用就精确取；解不出时，<b>只有单条款卡</b>按唯一那条给。
     *
     * <p>协议经常把描述码送成 0（尤其我方自己的发动），单条款卡（灰流丽、交闪）按唯一那条给全文。
     * <b>多条款卡绝不猜</b>：曾按"唯一含『发动』的条款"兜底，结果魔女术的创造的 ① 不含"发动"、
     * 只有墓地的 ② 含——被兜成 ②，记录里出现"发动…②：自己结束阶段…"的事实错误
     * （2026-11-14 实测反馈）。原始帧也救不了：MDPro3 分叉的 CHAINING desc 是跨卡共享的
     * 类别值（实测 28 帧无一匹配 卡号&lt;&lt;4|n，同一张卡两条效果同值），协议本身不带效果序号；
     * 多条款卡只能留白，条款正文交给快照 [连锁]/[候选卡面]。
     */
    public Optional<String> firedClause(long passcode, long description) {
        Optional<String> exact = clauseOf(passcode, description);
        if (exact.isPresent()) {
            return exact;
        }
        List<String> clauses = clauses(passcode);
        return clauses.size() == 1 ? Optional.of(clauses.getFirst()) : Optional.empty();
    }

    /** 只要卡名（记录行里点名"响应的是谁"用）；卡库无资料时给卡号，绝不编名字。 */
    public String name(long passcode) {
        if (passcode <= 0) {
            return "未知卡";
        }
        return facts.byPasscode(passcode)
                .map(CardFact::name)
                .orElse("卡(" + passcode + ")");
    }

    private String cardLine(CardFact fact) {
        StringBuilder sb = new StringBuilder(fact.label());
        StringJoiner meta = new StringJoiner("｜");
        if (!isBlank(fact.cardType())) {
            meta.add(fact.cardType());
        }
        // 攻守只对怪兽有意义：卡片库里魔法/陷阱的 atk 字段是 0（不是空），
        // 无条件拼会印出「〔通常魔法｜ATK0〕」这种既错又占位的字段（2026-11-14 实测反馈）。
        if (fact.isMonster() && fact.atk() != null) {
            meta.add("ATK" + fact.atk() + (fact.defense() == null ? "" : "/DEF" + fact.defense()));
        }
        if (meta.length() > 0) {
            sb.append('〔').append(meta).append('〕');
        }
        return sb.toString();
    }


    /**
     * 卡面效果按 ①②③ 切条（保留标记本身）；没有编号的卡整段作为一条。
     *
     * <p>必须逐条：只给"从 ① 起的前 N 字"会把多效果卡后面的条款整段截掉。
     */
    public List<String> clauses(long passcode) {
        if (passcode <= 0) {
            return List.of();
        }
        return facts.byPasscode(passcode)
                .map(CardFact::effect)
                .filter(text -> text != null && !text.isBlank())
                .map(CardText::splitClauses)
                .orElse(List.of());
    }

    private static List<String> splitClauses(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int first = flat.indexOf("①：");
        if (first > 0) {
            flat = flat.substring(first);
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 1 < flat.length(); i++) {
            if (CLAUSE_MARKS.indexOf(flat.charAt(i)) >= 0
                    && (flat.charAt(i + 1) == '：' || flat.charAt(i + 1) == ':')) {
                starts.add(i);
            }
        }
        if (starts.isEmpty()) {
            return List.of(flat);
        }
        List<String> clauses = new ArrayList<>(starts.size());
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k);
            int to = k + 1 < starts.size() ? starts.get(k + 1) : flat.length();
            clauses.add(flat.substring(from, to).trim());
        }
        return List.copyOf(clauses);
    }

    /**
     * 这次发动的是哪一条条款；解不出返回空（调用方应改用 {@link #fired}，它不会留白）。
     *
     * <p>先把"能发动的条款"挑出来再按序号取——引擎的效果序号只数可发动效果（见类注释）。
     * effectCode 必须是本卡的效果描述码（{@link #isOwnEffectCode}）；"是否发动"带的是提示文 id，
     * 会在这里被判空，调用方必须退化到"列出全部条款"，不能当成"第一条/全部"。
     */
    public Optional<String> clauseOf(long passcode, long effectCode) {
        return clauseOf(passcode, clauses(passcode), effectCode);
    }

    public Optional<String> clauseOf(long passcode, List<String> clauses, long effectCode) {
        if (clauses.isEmpty() || !isOwnEffectCode(passcode, effectCode)) {
            return Optional.empty();
        }
        List<String> activated = clauses.stream().filter(c -> c.contains("发动")).toList();
        List<String> pool = activated.isEmpty() ? clauses : activated;
        int index = (int) (effectCode & 0xF);
        return index < pool.size() ? Optional.of(pool.get(index)) : Optional.empty();
    }

    /**
     * description 是否是"这张卡自己的效果描述码"（{@code 卡号<<4|序号}）。
     *
     * <p>必须验高位：MSG_SELECT_EFFECTYN（是否发动）带的是提示文 id（0/221/31/94/97/218…），
     * 高位不是卡号。不验的话 97（= 6&lt;&lt;4|1）会被读成"②"，给模型一个凭空捏造的效果定位。
     */
    public static boolean isOwnEffectCode(long passcode, long effectCode) {
        return passcode > 0 && effectCode > 0 && (effectCode >>> 4) == passcode;
    }

    /**
     * 永续魔法 / 永续陷阱 / 场地魔法——"发动卡"与"发动效果"是两回事的那一类
     * （卡面 ①②③ 要等卡表侧在场后另起的连锁才发动；通常魔法/陷阱发动卡 = 发动那条款）。
     */
    public boolean isContinuousLike(long passcode) {
        return facts.byPasscode(passcode)
                .map(f -> f.cardType() != null
                        && (f.cardType().contains("永续") || f.cardType().contains("场地")))
                .orElse(false);
    }

    private boolean isSpellOrTrap(long passcode) {
        return facts.byPasscode(passcode)
                .map(f -> "SPELL".equals(f.kind()) || "TRAP".equals(f.kind()))
                .orElse(false);
    }

    /**
     * 判定一次"发动"是发动了<b>卡本身</b>还是某条<b>效果</b>（2026-11-15 用户反馈：
     * 永续魔法发动卡被渲染成"发动…的效果"，记录与决策行都误导模型以为 ①② 已发动）。
     *
     * <p>两条独立信号按可靠度排序：
     * <ol>
     *   <li>描述码是本卡效果码（{@link #isOwnEffectCode}）→ 效果；</li>
     *   <li>来源区域是手牌/卡组 → 卡（手牌里的卡只能"发动卡"；"发动效果"必在场上）；</li>
     *   <li>魔法/陷阱（kind）且描述码为 0 → 卡（协议对发动卡送 0，效果发动带效果码；
     *       通常魔法/陷阱此时卡=效果，措辞也成立）；</li>
     *   <li>其余 → 无法判定，调用方按现状渲染（全部条款，不下结论）。</li>
     * </ol>
     */
    public ActivationKind activationKind(long passcode, long description, ZoneRef sourceZone) {
        if (isOwnEffectCode(passcode, description)) {
            return ActivationKind.EFFECT;
        }
        if (sourceZone != null
                && (sourceZone.kind() == ZoneKind.HAND || sourceZone.kind() == ZoneKind.DECK)) {
            return ActivationKind.CARD;
        }
        if (description == 0 && isSpellOrTrap(passcode)) {
            return ActivationKind.CARD;
        }
        return ActivationKind.UNKNOWN;
    }


    /**
     * <b>"这次要发动的是哪一条"</b>——本类存在的核心理由。
     *
     * <p>能定位就给那一条<b>全文</b>（2026-11-15 用户反馈：截断会把后半段的限制条件
     * ——"那之后…""这个效果在…不能发动"——切掉，而那正是"该不该发"的关键）；
     * 定位不到就列出全部条款；连卡都没有就明说缺资料。
     * <b>任何分支都不返回空串</b>：留白等于让模型自己编。
     */
    public String fired(long passcode, long effectCode) {
        List<String> clauses = clauses(passcode);
        if (clauses.isEmpty()) {
            return passcode <= 0 ? "" : MISSING_CARD;
        }
        Optional<String> fired = clauseOf(passcode, clauses, effectCode);
        return fired.orElseGet(() -> all(clauses));
    }

    public String all(List<String> clauses) {
        return all(clauses, ALL_TOTAL_LIMIT);
    }

    public String all(List<String> clauses, int totalLimit) {
        if (clauses.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(" ／ ");
        int used = 0;
        for (String clause : clauses) {
            String part = clip(clause, BRIEF_LIMIT);
            if (used > 0 && used + part.length() > totalLimit) {
                joiner.add("…（其余略）");
                break;
            }
            joiner.add(part);
            used += part.length();
        }
        return joiner.toString();
    }

    /**
     * 全文版条款并列（快照 [候选卡面] 用）：限制/抗性常在后面的条款
     * （『不会被效果破坏』『不能作为效果的对象』），每条款截到 140 字会让
     * "选了等于白付代价"的判断依据整段消失。totalLimit 只挡病态长文本，
     * 正常卡面全文可容纳。
     */
    public String allFull(List<String> clauses, int totalLimit) {
        if (clauses.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(" ／ ");
        int used = 0;
        for (String clause : clauses) {
            if (used > 0 && used + clause.length() > totalLimit) {
                joiner.add("…（其余略）");
                break;
            }
            joiner.add(clause);
            used += clause.length();
        }
        return joiner.toString();
    }

    /**
     * 并列展示用的一行：{@code 闪刀姬-零衣(26077387)〔效果／怪兽｜…〕：② 这张卡在墓地…}。
     *
     * <p>用于"可发动效果 / 可连锁 / 是否发动"这些<b>要投票</b>的候选——
     * 只写卡名，模型就只能按记忆里的第一条效果判断。
     */
    public String effectLine(long passcode, long effectCode) {
        String text = fired(passcode, effectCode);
        return text.isEmpty() ? card(passcode) : card(passcode) + "：" + text;
    }

    public static String brief(String clause) {
        return clip(clause, BRIEF_LIMIT);
    }

    /** 条款序号（{@code ①}～{@code ⑧}）；条款不是以标记开头时返回空串。 */
    public static String markOf(String clause) {
        if (clause == null || clause.isBlank()) {
            return "";
        }
        char first = clause.strip().charAt(0);
        return CLAUSE_MARKS.indexOf(first) >= 0 ? String.valueOf(first) : "";
    }

    /** 去掉开头的「①：」——编号已单独给出时（请求行），正文不必再带一遍。 */
    public static String bodyOf(String clause) {
        if (clause == null) {
            return "";
        }
        String text = clause.strip();
        if (markOf(text).isEmpty()) {
            return text;
        }
        text = text.substring(1);
        if (text.startsWith("：") || text.startsWith(":")) {
            text = text.substring(1);
        }
        return text.strip();
    }

    public String firedSentence(long passcode, long effectCode) {
        String text = fired(passcode, effectCode);
        return text.isEmpty() ? card(passcode) : card(passcode) + " 的 " + text;
    }


    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.strip();
        if (flat.length() <= limit) {
            return flat;
        }
        int dot = flat.lastIndexOf('。', limit);
        return (dot > limit / 3 ? flat.substring(0, dot + 1) : flat.substring(0, limit)) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
