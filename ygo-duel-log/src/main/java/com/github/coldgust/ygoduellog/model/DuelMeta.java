package com.github.coldgust.ygoduellog.model;

import java.util.List;

/** 一局对局的元信息。 */
public record DuelMeta(
        String duelId,
        String path,
        int turns,
        String winner,
        String endReason,
        List<Integer> seats
) {

    public DuelMeta {
        seats = seats == null ? List.of() : List.copyOf(seats);
    }
}
