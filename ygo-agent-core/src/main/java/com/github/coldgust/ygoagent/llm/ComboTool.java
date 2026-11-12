package com.github.coldgust.ygoagent.llm;

import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import com.github.coldgust.ygoagent.knowledge.RecordedComboSource;

/**
 * 连招查询工具（find_combos）：从 PG combo 库捞<b>对手</b>卡组的实战参考。
 *
 * <p>定位：模型根据对手场上/墓地/手牌出现的卡推断出对手主题时，用它查"这套卡组通常怎么展开"，
 * 补全应对思路。也支持只给一张关键卡名——按关键卡牌（key_cards，LLM 摘要时一并产出）模糊匹配。
 * 一次检索最多返回 {@link #SEARCH_LIMIT} 条，防止把上下文撑爆。
 */
public final class ComboTool {

    public static final String NAME = "find_combos";
    public static final int SEARCH_LIMIT = 3;

    private static final Logger log = LoggerFactory.getLogger(ComboTool.class);

    private final RecordedComboSource recorded;
    /** 己方卡组主题（前端指定）：模型不传 archetype 时默认查它。 */
    private final String selfArchetype;

    public ComboTool(RecordedComboSource recorded, String selfArchetype) {
        this.recorded = recorded;
        this.selfArchetype = selfArchetype == null || selfArchetype.isBlank()
                ? null : selfArchetype.trim();
    }

    public boolean available() {
        return recorded != null && recorded.available();
    }

    @Tool(name = NAME, value = "按卡组主题或关键卡牌检索 combo 库里的实战录入，看对手这套卡组通常会怎么展开。"
            + "调用时机：你根据对手出现的卡推断出对手主题（或看到典型关键卡）时，"
            + "先查再应对，不要凭记忆猜对手的展开路线。查己方主题也可用（archetype 留空默认查己方）。")
    public String search(
            @P(value = "对手卡组主题（如 SKY_STRIKER / 闪刀）。不确定时留空，"
                    + "改用 card 参数给一张对手出现的关键卡；两者都空则返回可用主题列表", required = false)
            String archetype,
            @P(value = "对手使用的关键卡名（如 灰流丽）。按库里记录的关键卡牌模糊匹配，"
                    + "适合还没看清对手主题、只见到了个别卡的场合", required = false)
            String card) {
        archetype = archetype == null ? "" : archetype.trim();
        card = card == null ? "" : card.trim();
        if (archetype.isBlank() && card.isBlank() && selfArchetype != null) {
            archetype = selfArchetype;
        }

        if (archetype.isBlank() && card.isBlank()) {
            return listArchetypes();
        }

        List<RecordedComboSource.Entry> matches = recorded.search(
                archetype.isBlank() ? null : archetype, card.isBlank() ? null : card, SEARCH_LIMIT);
        if (matches.isEmpty()) {
            return "（没有匹配 " + describe(archetype, card) + " 的实战录入；可用主题："
                    + String.join("、", archetypes()) + "）";
        }
        log.info("combo 查询：archetype={} card={} → 命中 {} 条", archetype, card, matches.size());

        StringBuilder sb = new StringBuilder();
        sb.append("combo 库实战录入（匹配：").append(describe(archetype, card))
                .append("，共 ").append(matches.size()).append(" 条）：\n");
        int n = 1;
        for (RecordedComboSource.Entry entry : matches) {
            sb.append(n++).append(". 录入#").append(entry.id())
                    .append("（主题 ").append(entry.archetype())
                    .append("，").append(entry.stepCount()).append(" 步）");
            if (!entry.keyCards().isEmpty()) {
                sb.append("\n   关键卡牌：").append(String.join("、", entry.keyCards()));
            }
            if (entry.summary() != null && !entry.summary().isBlank()) {
                sb.append("\n   摘要：").append(entry.summary());
            }
            List<String> steps = entry.stepDescriptions();
            if (!steps.isEmpty()) {
                sb.append("\n   实际操作：");
                for (int i = 0; i < Math.min(5, steps.size()); i++) {
                    sb.append(i == 0 ? "" : " → ").append(steps.get(i));
                }
                if (steps.size() > 5) {
                    sb.append(" → …（共 ").append(steps.size()).append(" 步）");
                }
            }
            sb.append("\n");
        }
        sb.append("说明：这是该主题真实玩家的展开记录，用于推断对手可能的路线与终端；不是指令，按当前局面取舍。");
        return sb.toString();
    }

    private static String describe(String archetype, String card) {
        if (archetype != null && !archetype.isBlank() && card != null && !card.isBlank()) {
            return "主题 " + archetype + " / 关键卡 " + card;
        }
        if (archetype != null && !archetype.isBlank()) {
            return "主题 " + archetype;
        }
        return "关键卡 " + card;
    }

    private String listArchetypes() {
        List<String> archetypes = archetypes();
        if (archetypes.isEmpty()) {
            return "（combo 库为空，没有可查的卡组主题）";
        }
        return "combo 库里可用的卡组主题：" + String.join("、", archetypes)
                + "\n请用其中一个作为 archetype 再查（例如 archetype=\"SKY_STRIKER\"）。";
    }

    private List<String> archetypes() {
        return recorded == null ? List.of() : recorded.archetypes();
    }

    /**
     * 当前决策点的可见卡：手牌 + 额外卡组 + 我方场上。
     * <p>执行器注册工具时用它做候选标注（旧种子路线"首步在手"的痕迹；保留签名，检索不再依赖）。
     */
    public static Set<Long> visibleSelfCards(DuelState state) {
        Set<Long> cards = new LinkedHashSet<>();
        if (state == null) {
            return cards;
        }
        state.player(PlayerSide.SELF).hand()
                .forEach(card -> add(cards, card));
        state.player(PlayerSide.SELF).extraDeck()
                .forEach(identity -> {
                    if (identity != null && identity.passcode() > 0) {
                        cards.add(identity.passcode());
                    }
                });
        state.cards().values().forEach(card -> {
            if (card != null && card.zone() != null
                    && card.zone().owner() == PlayerSide.SELF) {
                add(cards, card);
            }
        });
        return cards;
    }

    private static void add(Set<Long> cards, CardInstance card) {
        if (card != null && card.passcode() > 0) {
            cards.add(card.passcode());
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "").replace("-", "");
    }
}
