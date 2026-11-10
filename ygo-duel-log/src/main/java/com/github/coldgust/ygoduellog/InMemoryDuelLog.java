package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import com.github.coldgust.ygoduellog.query.DecisionRecord;
import com.github.coldgust.ygoduellog.query.DuelInfo;
import com.github.coldgust.ygoduellog.query.DuelLog;
import com.github.coldgust.ygoduellog.query.EventFilter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public final class InMemoryDuelLog implements DuelRecorder, DuelLog {

    private record Context(int turn, String phase, long turnStartWallMillis) {
    }

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duel-log-writer");
        t.setDaemon(true);
        return t;
    });

    private final List<DuelEvent> events = new ArrayList<>();
    private final List<Consumer<DuelEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final List<DuelEventSink> sinks = new CopyOnWriteArrayList<>();

    private volatile Context context = new Context(0, "NONE", System.currentTimeMillis());
    private final AtomicLong seqCounter = new AtomicLong();
    private volatile boolean closed;
    private int selfSeat = -1;


    @Override
    public long append(String actor, DuelEventKind kind, DuelEventPayload payload) {
        if (closed) {
            return -1;
        }
        Context ctx = context;
        long now = System.currentTimeMillis();
        long gameClockMs = Math.max(0, now - ctx.turnStartWallMillis());
        // seq 在提交时分配（提交顺序 = 单写者消费顺序 = 落盘顺序），同步返回供决策配对；
        // 分配+入队须原子，否则并发提交时 seq 与落盘顺序可能错位（内容不丢，仅乱序）
        final DuelEvent event;
        synchronized (this) {
            long seq = seqCounter.incrementAndGet();
            event = new DuelEvent(seq, Instant.ofEpochMilli(now),
                    gameClockMs, ctx.turn(), ctx.phase(), actor, kind, payload);
            writer.execute(() -> doAppend(event));
        }
        return event.seq();
    }

    @Override
    public void updateContext(int turn, String phase, long turnStartWallMillis) {
        context = new Context(turn, phase, turnStartWallMillis);
    }

    @Override
    public void subscribe(Consumer<DuelEvent> listener) {
        subscribers.add(listener);
    }

    @Override
    public void attachSink(DuelEventSink sink) {
        sinks.add(sink);
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (DuelEventSink sink : sinks) {
            sink.close();
        }
    }

    public void markSelfSeat(int seat) {
        this.selfSeat = seat;
    }

    private void doAppend(DuelEvent event) {
        synchronized (events) {
            events.add(event);
        }
        for (DuelEventSink sink : sinks) {
            try {
                sink.write(event);
            } catch (RuntimeException e) {
                // 落盘失败不阻断记录，但必须留痕（否则数据丢失无声无息）
                LoggerFactory.getLogger(InMemoryDuelLog.class)
                        .warn("事件落盘失败 seq={} kind={}", event.seq(), event.kind(), e);
            }
        }
        for (Consumer<DuelEvent> listener : subscribers) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
            }
        }
    }


    @Override
    public DuelInfo duelInfo() {
        DuelEventPayload.DuelStarted started = null;
        DuelEventPayload.DuelEnded ended = null;
        int totalTurns = 0;
        for (DuelEvent e : snapshot()) {
            if (e.payload() instanceof DuelEventPayload.DuelStarted s) {
                started = s;
            } else if (e.payload() instanceof DuelEventPayload.DuelEnded end) {
                ended = end;
            } else if (e.payload() instanceof DuelEventPayload.TurnStarted t) {
                totalTurns = Math.max(totalTurns, t.turnNumber());
            }
        }
        return new DuelInfo(started, selfSeat, totalTurns, ended != null,
                ended == null ? null : ended.winner(), ended == null ? null : ended.reason());
    }

    @Override
    public List<DuelEvent> events(EventFilter filter) {
        List<DuelEvent> out = new ArrayList<>();
        for (DuelEvent e : snapshot()) {
            if (filter == null || filter.test(e)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<DuelEvent> turn(int turnNumber) {
        return events(EventFilter.ofTurn(turnNumber));
    }

    @Override
    public List<DecisionRecord> decisions(String player) {
        List<DecisionRecord> out = new ArrayList<>();
        DuelEvent pending = null;
        for (DuelEvent e : snapshot()) {
            if (e.payload() instanceof DuelEventPayload.DecisionRequested req) {
                if (player == null || player.equals(req.player())) {
                    pending = e;
                }
            } else if (pending != null && e.payload() instanceof DuelEventPayload.DecisionMade made
                    && made.requestSeq() == pending.seq()
                    && (player == null || player.equals(made.player()))) {
                out.add(new DecisionRecord(pending.seq(), e.seq(), made.player(), made.requestType(),
                        pending.turn(), requestSummary(pending), "MADE", made.selectionSummary(),
                        made.source(), made.thinkMs(), made.retryCount()));
                pending = null;
            } else if (pending != null && e.payload() instanceof DuelEventPayload.DecisionSkipped skipped
                    && skipped.requestSeq() == pending.seq()
                    && (player == null || player.equals(skipped.player()))) {
                out.add(new DecisionRecord(pending.seq(), e.seq(), skipped.player(), skipped.requestType(),
                        pending.turn(), requestSummary(pending), "SKIPPED", skipped.reason(),
                        null, -1, 0));
                pending = null;
            }
        }
        return List.copyOf(out);
    }

    private static String requestSummary(DuelEvent requested) {
        return requested.payload() instanceof DuelEventPayload.DecisionRequested req ? req.summary() : "";
    }

    @Override
    public DuelState stateAt(long seq) {
        DuelState state = null;
        for (DuelEvent e : snapshot()) {
            if (e.seq() > seq) {
                break;
            }
            if (e.payload() instanceof DuelEventPayload.StateSnapshot s) {
                state = s.duelState();
            }
        }
        return state;
    }

    @Override
    public long latestSeq() {
        synchronized (events) {
            return events.isEmpty() ? 0 : events.getLast().seq();
        }
    }

    @Override
    public List<DuelEvent> since(long seq) {
        return events(EventFilter.all().fromSeq(seq + 1));
    }

    private List<DuelEvent> snapshot() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }


    public void awaitDrain() {
        try {
            writer.shutdown();
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closed = true;
    }
}
