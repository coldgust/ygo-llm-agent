package com.github.coldgust.ygoagent.decision.planexec.prompt;

import com.github.coldgust.ygoagent.decision.planexec.CardFact;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 卡面文本的<b>唯一渲染出口</b>：凡是给模型看的"这是哪张卡、这是它的哪一条效果、这条效果写了什么"，
 * 都必须经过这里。
 *
 * <p>为什么要收成一层：这类信息以前散在七八个方法里（卡名一个、条款切分一个、效果码解码一个、
 * 渲染又各写一套），于是同一个病反复发作——模型读不懂引擎内部码，只能凭记忆猜，并且每次都猜错：
 *
 * <ul>
 *   <li>链环只给"从 ① 起的前 120 字" → 对方「王之仆人-黑魔术师」发动 ③（破坏我方全部后场），
 *       模型只看到 ①②，判"弃权保留两张速攻与机关"，结果三张全被破坏；</li>
 *   <li>`是否发动：闪刀姬-零衣(26077387) [效果#417238193]` → 引擎问的是 ②（墓地起跳），
 *       模型按 ①（解放自身）判断"解放不了"，白丢一次免费的资源回收；</li>
 *   <li>链环的候选改成"解不出就不写" → 干脆留白，比截断更糟。</li>
 * </ul>
 *
 * <p>三条硬约定，靠这个类在结构上保证：
 * <ol>
 *   <li><b>绝不输出裸效果码</b>（{@code [效果#...]}）。模型读不懂数字，只会去猜。</li>
 *   <li><b>能定位条款就给那一条原文</b>；定位不到就<b>列出全部条款</b>，不许留白。</li>
 *   <li>卡库查不到时明说"卡库无资料"，而不是给空字符串——留白会让模型自己编。</li>
 * </ol>
 *
 * <p>效果描述码的编码：{@code desc = 脚本卡号 << 4 | 序号}，低 4 位是该卡第几个
 * <b>可发动</b>效果（只数含"发动"的条款）。实测：「王之仆人-黑魔术师」① 是"当作黑魔术师使用"、
 * 不占号，所以 0→②、1→③；而 ①②③ 都能发动的卡（如「未来之柱-奇亚诺丝」）序号与印刷顺序一致。
 *
 * <p><b>但这个编码只属于"效果描述"字段</b>：连锁（MSG_SELECT_CHAIN）与主阶段（MSG_SELECT_IDLECMD）
 * 里带的就是它。而"是否发动"（MSG_SELECT_EFFECTYN）的 description <b>不是</b>效果序号——ocgcore 在
 * 触发型确认里塞的是提示文 id（0 / 221 / 31 / 94 / 97 / 218……），高位根本不是卡号。所以定位条款前
 * 必须先用 {@link #isOwnEffectCode(long, long)} 验一下：97 的低 4 位是 1，不验就会把一张多效果卡
 * 说成"这次要发动的是 ②"。实机事故（seq=10）：奇亚诺丝①特召成功后引擎问的是 <b>②</b>（从卡组特召
 * 露世），desc 解不出 → 退回"列出全部条款"，可上下文仍写着"这次要发动的是：①…／②…／③…"，模型读成
 * ①，答"①已用过、再丢魔法卡无净收益 → 不发动"，白丢一次免费的露世特召。
 */
public final class CardText {

    /** 条款标记（多效果卡）。①②③… 是印刷顺序，和引擎的"第 N 个可发动效果"不是一回事。 */
    private static final String CLAUSE_MARKS = "①②③④⑤⑥⑦⑧";

    /** 单条条款的默认长度：给"这次发动的就是它"用，要能读完发动条件。 */
    public static final int FOCUS_LIMIT = 260;
    /** 单条条款的短版本：并列展示多条时用。 */
    public static final int BRIEF_LIMIT = 140;
    /** 一次列出全部条款时的总预算。 */
    public static final int ALL_TOTAL_LIMIT = 400;

    private static final String MISSING_CARD = "（卡库无这张卡的资料，不要凭印象编造效果）";

    private final CardFacts facts;

    public CardText(CardFacts facts) {
        this.facts = facts;
    }

    /* ======================= 一、卡的身份 ======================= */

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

    private String cardLine(CardFact fact) {
        StringBuilder sb = new StringBuilder(fact.label());
        StringJoiner meta = new StringJoiner("｜");
        if (!isBlank(fact.cardType())) {
            meta.add(fact.cardType());
        }
        if (fact.atk() != null) {
            meta.add("ATK" + fact.atk() + (fact.defense() == null ? "" : "/DEF" + fact.defense()));
        }
        if (meta.length() > 0) {
            sb.append('〔').append(meta).append('〕');
        }
        return sb.toString();
    }

    /* ======================= 二、条款 ======================= */

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

    /** 同 {@link #clauseOf(long, long)}，条款由调用方切好（连锁段一次要渲染多张卡）。 */
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

    /* ======================= 三、渲染 ======================= */

    /**
     * <b>"这次要发动的是哪一条"</b>——本类存在的核心理由。
     *
     * <p>能定位就给那一条原文；定位不到就列出全部条款；连卡都没有就明说缺资料。
     * <b>任何分支都不返回空串</b>：留白等于让模型自己编。
     */
    public String fired(long passcode, long effectCode) {
        List<String> clauses = clauses(passcode);
        if (clauses.isEmpty()) {
            return passcode <= 0 ? "" : MISSING_CARD;
        }
        Optional<String> fired = clauseOf(passcode, clauses, effectCode);
        return fired.map(clause -> clip(clause, FOCUS_LIMIT)).orElseGet(() -> all(clauses));
    }

    /** 全部条款，用 {@code ／} 连接；超预算时截尾并注明。 */
    public String all(long passcode, int totalLimit) {
        return all(clauses(passcode), totalLimit);
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
     * 并列展示用的一行：{@code 闪刀姬-零衣(26077387)〔效果／怪兽｜…〕：② 这张卡在墓地…}。
     *
     * <p>用于"可发动效果 / 可连锁 / 是否发动"这些<b>要投票</b>的候选——
     * 只写卡名，模型就只能按记忆里的第一条效果判断。
     */
    public String effectLine(long passcode, long effectCode) {
        String text = fired(passcode, effectCode);
        return text.isEmpty() ? card(passcode) : card(passcode) + "：" + text;
    }

    /** 除"这次发动的那条"以外的条款（避免同一句话在上下文里出现两遍）。 */
    public List<String> others(long passcode, long effectCode) {
        List<String> clauses = clauses(passcode);
        Optional<String> fired = clauseOf(passcode, clauses, effectCode);
        if (fired.isEmpty()) {
            return clauses;
        }
        return clauses.stream().filter(c -> !c.equals(fired.get())).toList();
    }

    /** 条款的短版本（并列展示多条时用）。 */
    public static String brief(String clause) {
        return clip(clause, BRIEF_LIMIT);
    }

    /** {@code 「卡名」的 ②：…}，用于 [当前效果] 这类"你在结算哪个效果"。 */
    public String firedSentence(long passcode, long effectCode) {
        String text = fired(passcode, effectCode);
        return text.isEmpty() ? card(passcode) : card(passcode) + " 的 " + text;
    }

    /* ======================= 工具 ======================= */

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
