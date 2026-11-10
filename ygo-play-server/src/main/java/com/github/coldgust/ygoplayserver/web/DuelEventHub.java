package com.github.coldgust.ygoplayserver.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DuelEventHub {

    private static final Logger log = LoggerFactory.getLogger(DuelEventHub.class);

    private static final int MAX_HISTORY = 200;

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Deque<HistoryEntry>> history = new ConcurrentHashMap<>();

    private record HistoryEntry(String eventName, Object payload) {
    }

    public SseEmitter register(String duelId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由前端/stop 关闭
        // 先回放历史（订阅前发生的事件不丢失），再挂订阅者
        Deque<HistoryEntry> past = history.get(duelId);
        if (past != null) {
            for (HistoryEntry entry : past) {
                try {
                    emitter.send(SseEmitter.event().name(entry.eventName()).data(entry.payload()));
                } catch (IOException e) {
                    return emitter;
                }
            }
        }
        List<SseEmitter> list = subscribers.computeIfAbsent(duelId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("duelId", duelId, "replayed", past == null ? 0 : past.size())));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(String duelId, String eventName, Object payload) {
        Deque<HistoryEntry> past = history.computeIfAbsent(duelId, k -> new ArrayDeque<>());
        synchronized (past) {
            past.addLast(new HistoryEntry(eventName, payload));
            while (past.size() > MAX_HISTORY) {
                past.removeFirst();
            }
        }
        List<SseEmitter> emitters = subscribers.get(duelId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public void complete(String duelId, String eventName, Object payload) {
        publish(duelId, eventName, payload);
        List<SseEmitter> emitters = subscribers.remove(duelId);
        if (emitters != null) {
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    public int subscriberCount(String duelId) {
        List<SseEmitter> emitters = subscribers.get(duelId);
        return emitters == null ? 0 : emitters.size();
    }
}
