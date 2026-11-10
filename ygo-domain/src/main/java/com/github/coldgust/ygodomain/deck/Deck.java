package com.github.coldgust.ygodomain.deck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一副卡组（官方术语：Main Deck 40–60 张 / Extra Deck 0–15 张 / Side Deck 0–15 张；
 * 同名卡（含马甲 alias）最多 3 张）。
 *
 * @param main  主卡组 passcode（顺序无关）
 * @param side  副卡组 passcode（换备用）
 */
public record Deck(List<Long> main, List<Long> extra, List<Long> side) {

    public static final int MAIN_MIN = 40;
    public static final int MAIN_MAX = 60;
    public static final int EXTRA_MAX = 15;
    public static final int SIDE_MAX = 15;
    public static final int COPIES_LIMIT = 3;

    public Deck {
        main = main == null ? List.of() : List.copyOf(main);
        extra = extra == null ? List.of() : List.copyOf(extra);
        side = side == null ? List.of() : List.copyOf(side);
    }

    public static Deck empty() {
        return new Deck(List.of(), List.of(), List.of());
    }

    /**
     * 依据规则书的构造校验。alias 合并计数需要卡库知识，
     * 这里只做同 passcode 计数校验；调用方有卡库时应另行做 alias 合并校验。
     *
     * @return 违规项列表；空列表 = 合法
     */
    public List<String> validate() {
        List<String> issues = new ArrayList<>();
        if (main.size() < MAIN_MIN || main.size() > MAIN_MAX) {
            issues.add("Main Deck 必须为 %d–%d 张，当前 %d 张".formatted(MAIN_MIN, MAIN_MAX, main.size()));
        }
        if (extra.size() > EXTRA_MAX) {
            issues.add("Extra Deck 最多 %d 张，当前 %d 张".formatted(EXTRA_MAX, extra.size()));
        }
        if (side.size() > SIDE_MAX) {
            issues.add("Side Deck 最多 %d 张，当前 %d 张".formatted(SIDE_MAX, side.size()));
        }
        checkCopies("Main Deck", main, issues);
        checkCopies("Extra Deck", extra, issues);
        checkCopies("Side Deck", side, issues);
        return issues;
    }

    private static void checkCopies(String label, List<Long> codes, List<String> issues) {
        Set<Long> seen = new LinkedHashSet<>();
        for (Long code : codes) {
            if (!seen.add(code)) {
                continue;
            }
            long count = codes.stream().filter(c -> c.equals(code)).count();
            if (count > COPIES_LIMIT) {
                issues.add("%s 中 passcode %d 出现 %d 次（上限 %d）".formatted(label, code, count, COPIES_LIMIT));
            }
        }
    }
}
