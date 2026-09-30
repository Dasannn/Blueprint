package com.dasannn.socialblueprint.domain.duel;

import com.dasannn.socialblueprint.domain.PlayerId;

import java.util.Objects;

/**
 * Immutable participant in a duel per ARCHITECTURE.md §4.
 * Represents a row in the duel_participant table.
 */
public record DuelParticipant(
        PlayerId playerId,
        String side
) {
    public DuelParticipant {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(side, "side must not be null");
        if (side.isBlank()) {
            throw new IllegalArgumentException("side must not be blank");
        }
    }
}
