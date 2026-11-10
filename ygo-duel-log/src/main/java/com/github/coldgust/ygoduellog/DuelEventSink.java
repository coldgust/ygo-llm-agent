package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.event.DuelEvent;

public interface DuelEventSink {

    void write(DuelEvent event);

    void close();
}
