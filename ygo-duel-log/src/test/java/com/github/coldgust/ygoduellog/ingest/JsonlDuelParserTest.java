package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.ingest.JsonlDuelParser;
import com.github.coldgust.ygoduellog.model.DecisionEpisode;
import com.github.coldgust.ygoduellog.model.DuelRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlDuelParserTest {

    private static final String SNAPSHOT = """
            {"seq":4,"turn":1,"phase":"MAIN_PHASE_1","kind":"STATE_SNAPSHOT","payload":{"duelState":{
              "players":{"SELF":{"lifePoints":7500,"hand":[{"identity":{"passcode":63166095}}]},
                         "OPPONENT":{"lifePoints":8000,
                           "hand":[{"identity":"UnknownCard"}]}},
              "cards":{"1":{"identity":{"passcode":63288573},
                            "zone":{"kind":"EXTRA_MONSTER_ZONE","owner":"SELF"}}}
            }}}
            """;

    @Test
    void splitsEpisodesAndValidates() {
        List<String> lines = List.of(
                "{\"seq\":1,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DUEL_STARTED\",\"payload\":{\"players\":[{\"seat\":0},{\"seat\":1}]}}",
                SNAPSHOT,
                "{\"seq\":5,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DECISION_REQUESTED\",\"payload\":{\"requestType\":\"MainPhaseRequest\",\"candidateCount\":2}}",
                "{\"seq\":6,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DECISION_CONTEXT\",\"payload\":{\"requestType\":\"MainPhaseRequest\",\"candidateCount\":2,\"candidates\":[{\"kind\":\"activate\",\"passcode\":63166095,\"zone\":\"SELF:HAND#0\",\"description\":0}]}}",
                "{\"seq\":7,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DECISION_MADE\",\"payload\":{\"requestSeq\":5,\"selectionSummary\":\"Activate\",\"source\":\"PLAYER\",\"thinkMs\":12}}",
                "{\"seq\":8,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DECISION_REQUESTED\",\"payload\":{\"requestType\":\"CardSelectRequest\",\"candidateCount\":3}}",
                "{\"seq\":9,\"turn\":1,\"phase\":\"MAIN_PHASE_1\",\"kind\":\"DECISION_SKIPPED\",\"payload\":{\"requestSeq\":8,\"reason\":\"TIMEOUT\"}}",
                "{\"seq\":10,\"turn\":2,\"phase\":\"END_PHASE\",\"kind\":\"DUEL_ENDED\",\"payload\":{\"winner\":\"P0\",\"reason\":\"NORMAL\",\"totalTurns\":2}}"
        );

        DuelRecord record = new JsonlDuelParser().parse(Path.of("test-duel.jsonl"), lines);

        assertEquals("test-duel", record.meta().duelId());
        assertEquals(2, record.requested());
        assertEquals(1, record.answered());
        assertEquals(1, record.contextProvided());
        assertTrue(record.valid());
        assertEquals("P0", record.meta().winner());
        assertEquals(2, record.meta().turns());

        DecisionEpisode first = record.episodes().getFirst();
        assertEquals("MainPhaseRequest", first.requestType());
        assertEquals(7500, first.selfLp());
        assertEquals(List.of(63166095L), first.handPasscodes());
        assertEquals(List.of(63288573L), first.fieldCards());
        assertEquals("Activate", first.chosen());
        assertTrue(first.answered());

        DecisionEpisode second = record.episodes().get(1);
        assertFalse(second.answered());
        assertEquals("TIMEOUT", second.source());
    }
}
