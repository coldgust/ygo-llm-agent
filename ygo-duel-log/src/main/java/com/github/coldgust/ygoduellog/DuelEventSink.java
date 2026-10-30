package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.event.DuelEvent;

/**
 * 事件落盘目的地（JSONL 文件、数据库、远端…）。
 */
public interface DuelEventSink {

    void write(DuelEvent event);

    void close();
}
