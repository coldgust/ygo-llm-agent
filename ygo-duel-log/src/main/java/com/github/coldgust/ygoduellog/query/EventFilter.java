package com.github.coldgust.ygoduellog.query;

import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;

import java.util.Set;
import java.util.function.Predicate;

public record EventFilter(
        Long fromSeq,
        Long toSeq,
        Integer turn,
        Set<DuelEventKind> kinds,
        Set<String> actors
) implements Predicate<DuelEvent> {

    public EventFilter {
        kinds = kinds == null ? null : Set.copyOf(kinds);
        actors = actors == null ? null : Set.copyOf(actors);
    }

    public static EventFilter all() {
        return new EventFilter(null, null, null, null, null);
    }

    public static EventFilter ofTurn(int turn) {
        return new EventFilter(null, null, turn, null, null);
    }

    public static EventFilter ofKinds(DuelEventKind... kinds) {
        return new EventFilter(null, null, null, Set.of(kinds), null);
    }

    public static EventFilter ofActor(String actor) {
        return new EventFilter(null, null, null, null, Set.of(actor));
    }

    public EventFilter fromSeq(long seq) {
        return new EventFilter(seq, toSeq, turn, kinds, actors);
    }

    public EventFilter toSeq(long seq) {
        return new EventFilter(fromSeq, seq, turn, kinds, actors);
    }

    @Override
    public boolean test(DuelEvent e) {
        if (fromSeq != null && e.seq() < fromSeq) {
            return false;
        }
        if (toSeq != null && e.seq() > toSeq) {
            return false;
        }
        if (turn != null && e.turn() != turn) {
            return false;
        }
        if (kinds != null && !kinds.contains(e.kind())) {
            return false;
        }
        return actors == null || actors.contains(e.actor());
    }
}
