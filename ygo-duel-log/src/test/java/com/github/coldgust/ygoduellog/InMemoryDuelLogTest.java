package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import com.github.coldgust.ygoduellog.query.DecisionRecord;
import com.github.coldgust.ygoduellog.query.EventFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDuelLogTest {

    @TempDir
    Path tmp;

    @Test
    void eventsAreOrderedAndSeqMonotonic() {
        var log = new InMemoryDuelLog();
        log.append("SYSTEM", DuelEventKind.DUEL_STARTED,
                new DuelEventPayload.DuelStarted(List.of(
                        new DuelEventPayload.PlayerInfo(0, "甲", 40, 15),
                        new DuelEventPayload.PlayerInfo(1, "乙", 40, 15)), 5, 8000, 5, 1, 300));
        log.append("P0", DuelEventKind.TURN_STARTED, new DuelEventPayload.TurnStarted("P0", 1));
        log.awaitDrain();

        List<DuelEvent> all = log.events(EventFilter.all());
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).seq());
        assertEquals(2, all.get(1).seq());
        assertEquals(DuelEventKind.DUEL_STARTED, all.get(0).kind());
        assertEquals("甲", log.duelInfo().players().getFirst().name());
    }

    @Test
    void decisionsArePairedByRequestSeq() {
        var log = new InMemoryDuelLog();
        log.updateContext(2, "MAIN_PHASE_1", System.currentTimeMillis() - 1000);
        log.append("P1", DuelEventKind.DECISION_REQUESTED,
                new DuelEventPayload.DecisionRequested("P1", "MainPhaseRequest",
                        DuelEventPayload.DecisionRequested.Visibility.FULL, 6, "可通常召唤…"));
        log.append("P1", DuelEventKind.DECISION_MADE,
                new DuelEventPayload.DecisionMade(1, "P1", "MainPhaseRequest", "main summon 0",
                        "PLAYER", 1500, 0));
        log.append("P1", DuelEventKind.DECISION_REQUESTED,
                new DuelEventPayload.DecisionRequested("P1", "ChainRequest",
                        DuelEventPayload.DecisionRequested.Visibility.FULL, 1, "可连锁…"));
        log.append("P1", DuelEventKind.DECISION_SKIPPED,
                new DuelEventPayload.DecisionSkipped(3, "P1", "ChainRequest", "AUTO_DECLINE_CHAIN"));
        log.awaitDrain();

        List<DecisionRecord> decisions = log.decisions("P1");
        assertEquals(2, decisions.size());
        assertEquals("MADE", decisions.get(0).outcome());
        assertEquals("main summon 0", decisions.get(0).selectionSummary());
        assertEquals("SKIPPED", decisions.get(1).outcome());
        assertEquals("AUTO_DECLINE_CHAIN", decisions.get(1).selectionSummary());
        assertEquals(1500, decisions.get(0).thinkMs());
    }

    @Test
    void stateAtReturnsLatestSnapshotBeforeSeq() {
        var log = new InMemoryDuelLog();
        var state = new DuelState(
                5, 1, null, null, Map.of(), Map.of(),
                List.of(), null, 0);
        log.append("SYSTEM", DuelEventKind.STATE_SNAPSHOT, new DuelEventPayload.StateSnapshot(state));
        log.append("P0", DuelEventKind.LP_CHANGED, new DuelEventPayload.LpChanged("P0", -1000, 7000, "战斗伤害"));
        log.append("SYSTEM", DuelEventKind.STATE_SNAPSHOT, new DuelEventPayload.StateSnapshot(state));
        log.awaitDrain();

        assertNotNull(log.stateAt(1));
        assertNotNull(log.stateAt(2));
        assertNotNull(log.stateAt(3));
        assertEquals(2, log.events(EventFilter.all().fromSeq(2)).size());
        assertEquals(1, log.since(2).size());
    }

    @Test
    void jsonlSinkWritesReadableLines() throws Exception {
        Path file = tmp.resolve("duel.jsonl");
        var log = new InMemoryDuelLog();
        log.attachSink(new JsonlSink(file));
        log.append("P1", DuelEventKind.PHASE_ENTERED, new DuelEventPayload.PhaseEntered("BATTLE_START_STEP"));
        log.close();

        String line = Files.readString(file).strip();
        assertTrue(line.contains("\"kind\":\"PHASE_ENTERED\""));
        assertTrue(line.contains("BATTLE_START_STEP"));
        assertEquals(1, line.lines().count());
    }

    @Test
    void subscribersReceiveEventsRealtime() {
        var log = new InMemoryDuelLog();
        var received = new CopyOnWriteArrayList<Long>();
        log.subscribe(e -> received.add(e.seq()));
        log.append("P0", DuelEventKind.TURN_STARTED, new DuelEventPayload.TurnStarted("P0", 1));
        log.awaitDrain();
        assertEquals(1, received.size());
    }
}
