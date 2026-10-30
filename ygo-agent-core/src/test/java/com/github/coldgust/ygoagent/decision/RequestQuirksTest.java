package com.github.coldgust.ygoagent.decision;

import com.github.coldgust.ygoagent.decision.quirks.RequestQuirks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestQuirksTest {

    @Test
    void placeCountZeroWithZonesMeansOne() {
        assertEquals(1, RequestQuirks.placeZoneCount(0, 3));
        assertEquals(2, RequestQuirks.placeZoneCount(2, 3));
        assertEquals(3, RequestQuirks.placeZoneCount(9, 3));
        assertEquals(0, RequestQuirks.placeZoneCount(0, 0));
    }

    @Test
    void declareCardHasStableDefault() {
        assertEquals(14558127L, RequestQuirks.DEFAULT_DECLARE_CARD);
    }
}
