package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库来源的域名判定：单 workspace 里靠 `file_source` 前缀把四类知识分开。
 *
 * <p>为什么需要集中一处：规则检索（{@link RulebookTool}）与对局检索（{@link DuelRecallTool}）
 * 各自按来源过滤，分隔符却没法统一——`/documents/text` 允许 `rule:xxx`，
 * 而 `/documents/upload` 用文件名当来源、**拒绝冒号**（LightRAG 的 sanitize_filename 会 400），
 * 所以规则书只能叫 `rule-ocg-zh.pdf`。这里把 `:` `-` `_` 视作等价分隔符，
 * 两个工具就用同一套判定，不会因为换个写法就漏过滤。
 *
 * <p>历史数据的兼容：早期英文规则书 chunk 名是 `rulebook_v10_en_p1-p2`（无前缀），
 * 仍应判为规则域，不能因为"没写前缀"就丢掉。
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

    /** 对局域：必须是 `duel:` / `duel-`，不猜。 */
    public static boolean isDuel(String source) {
        return of(source) == Domain.DUEL;
    }

    private SourceDomain() {
    }
}
