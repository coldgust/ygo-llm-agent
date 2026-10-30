package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域名判定：分隔符 `:`/`-`/`_` 等价（upload 拒绝冒号，只能写成 `rule-xxx.pdf`）。
 */
class SourceDomainTest {

    @Test
    void ruleDomainAcceptsBothSeparatorsAndLegacyNames() {
        assertEquals(SourceDomain.Domain.RULE, SourceDomain.of("rule-ocg-zh.pdf"));
        assertEquals(SourceDomain.Domain.RULE, SourceDomain.of("rule:ocg-zh:p3-p5"));
        assertEquals(SourceDomain.Domain.RULE, SourceDomain.of("rulebook_v10_en_p1-p2"));

        assertTrue(SourceDomain.isRule("rule-ocg-zh.pdf"));
        assertTrue(SourceDomain.isRule("rulebook_v10_en_p1-p2"));
    }

    @Test
    void otherDomainsAreNeverTreatedAsRule() {
        // card-123 / duel-abc 这类不带域前缀的来源不能被当成规则放行
        assertFalse(SourceDomain.isRule("card-123"));
        assertFalse(SourceDomain.isRule("card:123"));
        assertFalse(SourceDomain.isRule("combo-sky-striker"));
        assertFalse(SourceDomain.isRule("duel-20261030-001"));
        assertFalse(SourceDomain.isRule("duel:20261030-001"));

        assertEquals(SourceDomain.Domain.CARD, SourceDomain.of("card-123"));
        assertEquals(SourceDomain.Domain.COMBO, SourceDomain.of("combo-sky"));
        assertEquals(SourceDomain.Domain.DUEL, SourceDomain.of("duel-20261030-001"));
    }

    @Test
    void unknownSourcesFallBackToRuleButNeverToDuel() {
        // 来源缺失/中文文件名：查规则的场景下按规则处理；但绝不能当成对局
        assertTrue(SourceDomain.isRule(null));
        assertTrue(SourceDomain.isRule(""));
        assertTrue(SourceDomain.isRule("规则书-中文.pdf"));
        assertFalse(SourceDomain.isDuel("规则书-中文.pdf"));

        assertTrue(SourceDomain.isDuel("duel:abc"));
        assertTrue(SourceDomain.isDuel("duel-abc"));
        assertFalse(SourceDomain.isDuel("rule-ocg-zh.pdf"));
    }
}
