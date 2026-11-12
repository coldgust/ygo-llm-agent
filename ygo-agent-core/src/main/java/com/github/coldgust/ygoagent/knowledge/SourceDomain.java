package com.github.coldgust.ygoagent.knowledge;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库来源的域名判定：单 workspace 里靠 `file_source` 前缀把四类知识分开。
 */
public final class SourceDomain {

    public enum Domain {
        RULE, CARD, COMBO, DUEL, UNKNOWN
    }

    private static final Pattern RULE = Pattern.compile("^(rule|rulebook)[:\\-_.\\s]|^(rulebook)");
    private static final Pattern CARD = Pattern.compile("^card[:\\-_.\\s]");
    private static final Pattern COMBO = Pattern.compile("^combo[:\\-_.\\s]");
    private static final Pattern DUEL = Pattern.compile("^duel[:\\-_.\\s]");

    public static Domain of(String source) {
        if (source == null || source.isBlank()) {
            return Domain.UNKNOWN;
        }
        String s = source.trim().toLowerCase(Locale.ROOT);
        if (RULE.matcher(s).find()) {
            return Domain.RULE;
        }
        if (CARD.matcher(s).find()) {
            return Domain.CARD;
        }
        if (COMBO.matcher(s).find()) {
            return Domain.COMBO;
        }
        if (DUEL.matcher(s).find()) {
            return Domain.DUEL;
        }
        return Domain.UNKNOWN;
    }

    /** 规则域：显式 `rule*`，或无法判断（来源缺失/中文文件名）时按规则处理——本工具就是查规则的。 */
    public static boolean isRule(String source) {
        Domain domain = of(source);
        return domain == Domain.RULE || domain == Domain.UNKNOWN;
    }

    public static boolean isDuel(String source) {
        return of(source) == Domain.DUEL;
    }

    private SourceDomain() {
    }
}
