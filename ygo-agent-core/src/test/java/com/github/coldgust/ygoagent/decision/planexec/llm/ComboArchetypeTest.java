package com.github.coldgust.ygoagent.decision.planexec.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComboArchetypeTest {

    @Test
    void chineseAndCodePointToTheSameDeck() {
        assertEquals("SKY_STRIKER", ComboArchetype.canonical("闪刀"));
        assertEquals("SKY_STRIKER", ComboArchetype.canonical("闪刀姬"));
        assertEquals("SKY_STRIKER", ComboArchetype.canonical("sky striker"));
        assertEquals("SKY_STRIKER", ComboArchetype.canonical("SKY-STRIKER"));
        assertTrue(ComboArchetype.matches("闪刀", "SKY_STRIKER"));
        assertTrue(ComboArchetype.matches("SKY_STRIKER", "闪刀"));
    }

    @Test
    void unrelatedDecksStaySeparate() {
        assertFalse(ComboArchetype.matches("闪刀", "烙印"));
        assertFalse(ComboArchetype.matches("", "闪刀"));
    }

    @Test
    void candidatesTryCanonicalCodeFirstThenTheRawSpelling() {
        assertEquals(List.of("SKY_STRIKER", "闪刀"), ComboArchetype.candidates("闪刀"));
        assertEquals(List.of("SKY_STRIKER"), ComboArchetype.candidates("SKY_STRIKER"));
        assertEquals(List.of(), ComboArchetype.candidates("  "));
    }
}
