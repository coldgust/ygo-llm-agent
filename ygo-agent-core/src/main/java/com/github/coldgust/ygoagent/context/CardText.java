package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import com.github.coldgust.ygoagent.knowledge.CardFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

public final class CardText {

    /** 条款标记（多效果卡）。①②③… 是印刷顺序，和引擎的"第 N 个可发动效果"不是一回事。 */
    private static final String CLAUSE_MARKS = "①②③④⑤⑥⑦⑧";

    /** 单条条款的默认长度：给"这次发动的就是它"用，要能读完发动条件。 */
    public static final int FOCUS_LIMIT = 260;
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

    public static String brief(String clause) {
        return clip(clause, BRIEF_LIMIT);
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
