package com.github.coldgust.ygoduellog.query;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoduellog.event.DuelEvent;

import java.util.List;

public interface DuelLog {

    DuelInfo duelInfo();

    List<DuelEvent> events(EventFilter filter);

    List<DuelEvent> turn(int turnNumber);

    List<DecisionRecord> decisions(String player);

    DuelState stateAt(long seq);

    long latestSeq();

    List<DuelEvent> since(long seq);
}
