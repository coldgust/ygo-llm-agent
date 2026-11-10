package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;

import java.util.function.Consumer;

public interface DuelRecorder {

    long append(String actor, DuelEventKind kind, DuelEventPayload payload);

    void updateContext(int turn, String phase, long turnStartWallMillis);

    void subscribe(Consumer<DuelEvent> listener);

    void attachSink(DuelEventSink sink);

    void close();
}
