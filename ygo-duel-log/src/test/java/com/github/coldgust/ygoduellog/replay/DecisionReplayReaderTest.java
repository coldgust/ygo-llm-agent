package com.github.coldgust.ygoduellog.replay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionReplayReaderTest {

    @Test
    void rebuildsRequestedContextAndAnswers() {
        List<String> lines = List.of(
                "{\"seq\":10,\"turn\":2,\"kind\":\"DECISION_REQUESTED\",\"payload\":{\"requestType\":\"MainPhaseRequest\",\"candidateCount\":2}}",
                "{\"seq\":11,\"turn\":2,\"kind\":\"DECISION_CONTEXT\",\"payload\":{\"requestType\":\"MainPhaseRequest\",\"candidateCount\":5,\"min\":null,\"max\":null,\"cancelable\":false}}",
                "{\"seq\":12,\"turn\":2,\"kind\":\"DECISION_MADE\",\"payload\":{\"requestSeq\":10,\"selectionSummary\":\"Summon\",\"source\":\"PLAYER\",\"thinkMs\":12}}",
                "{\"seq\":20,\"turn\":3,\"kind\":\"DECISION_REQUESTED\",\"payload\":{\"requestType\":\"CardSelectRequest\",\"candidateCount\":3}}",
                "{\"seq\":21,\"turn\":3,\"kind\":\"DECISION_CONTEXT\",\"payload\":{\"requestType\":\"CardSelectRequest\",\"candidateCount\":3,\"min\":1,\"max\":1,\"cancelable\":false}}",
                "{\"seq\":22,\"turn\":3,\"kind\":\"DECISION_SKIPPED\",\"payload\":{\"requestSeq\":20,\"reason\":\"TIMEOUT\"}}"
        );

        var decisions = DecisionReplayReader.read(lines);

        assertEquals(2, decisions.size());
        var first = decisions.get(0);
        assertEquals("MainPhaseRequest", first.requestType());
        assertEquals(5, first.candidateCount());
        assertEquals("PLAYER", first.source());
        assertTrue(first.answered());

        var second = decisions.get(1);
        assertEquals("CardSelectRequest", second.requestType());
        assertEquals(1, second.min());
        assertEquals(1, second.max());
        assertFalse(second.answered());
        assertNull(second.chosen());
        assertEquals("TIMEOUT", second.source());
    }

    @Test
    void ignoresMalformedLines() {
        var decisions = DecisionReplayReader.read(List.of("not-json", "{\"seq\":1}"));
        assertEquals(0, decisions.size());
    }
}
