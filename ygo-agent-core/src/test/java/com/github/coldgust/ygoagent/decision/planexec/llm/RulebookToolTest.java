package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单 workspace 多域共存：规则检索工具必须只吃规则域，别被卡牌/combo/对局片段串味。
 */
class RulebookToolTest {

    @Test
    void ruleDomainFilterExcludesOtherDomainsButKeepsLegacyRulebookSources() {
        // 带前缀的新数据
        assertTrue(RulebookTool.isRuleDomain("rule:rulebook_v10_en_p9-p10"));
        // 历史数据没有前缀，不能因此判成非规则
        assertTrue(RulebookTool.isRuleDomain("rulebook_v10_en_p9-p10"));
        assertTrue(RulebookTool.isRuleDomain("rulebook_v10_en"));
        // 来源缺失时按规则处理（本工具只用于查规则）
        assertTrue(RulebookTool.isRuleDomain(null));
        assertTrue(RulebookTool.isRuleDomain("  "));

        // 其他域必须排除（新前缀 + 旧命名）
        assertFalse(RulebookTool.isRuleDomain("card:23434538"));
        assertFalse(RulebookTool.isRuleDomain("card_23434538"));
        assertFalse(RulebookTool.isRuleDomain("combo:sky-striker"));
        assertFalse(RulebookTool.isRuleDomain("duel:20261030-001"));
    }
}
