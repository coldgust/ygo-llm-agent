package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.model.Candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

public final class Fingerprint {

    public static String stateHash(DuelState state) {
        StringBuilder b = new StringBuilder(512);
        b.append("rule=").append(state.duelRule())
                .append("|turn=").append(state.turnNumber())
                .append("|actor=").append(state.turnPlayer())
                .append("|phase=").append(state.phase())
                .append("|seat=").append(state.selfSeat());
        for (PlayerSide side : PlayerSide.values()) {
            var p = state.player(side);
            if (p == null) {
                continue;
            }
            b.append("\n").append(side)
                    .append(":lp=").append(p.lifePoints())
                    .append(":hand=").append(p.handCount())
                    .append(":deck=").append(p.mainDeckCount())
                    .append(":extra=").append(p.extraDeckCount())
                    .append(":gy=");
            p.graveyard().forEach(c -> b.append(c.passcode()).append(','));
            b.append(":ban=");
            p.banished().forEach(c -> b.append(c.passcode()).append(','));
        }
        b.append("\ncards=");
        state.cards().values().stream()
                .sorted(Comparator.comparingLong(CardInstance::instanceId))
                .forEach(c -> b.append(c.instanceId()).append(':')
                        .append(c.passcode()).append(':')
                        .append(c.zone().kind()).append(':')
                        .append(c.zone().owner()).append(':')
                        .append(c.zone().sequence()).append(':')
                        .append(c.position()).append(';'));
        return sha256(b.toString());
    }

    public static String requestHash(ChoiceRequest request, List<Candidate> candidates) {
        StringBuilder b = new StringBuilder(256);
        b.append(request.getClass().getName()).append('|');
        for (Candidate c : candidates) {
            b.append(c.id().value()).append(':').append(c.getClass().getSimpleName()).append(';');
        }
        b.append('|').append(request);
        return sha256(b.toString());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte x : hash) {
                hex.append(Character.forDigit((x >> 4) & 0xF, 16));
                hex.append(Character.forDigit(x & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Fingerprint() {
    }
}
