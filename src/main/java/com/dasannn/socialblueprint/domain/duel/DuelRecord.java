package com.dasannn.socialblueprint.domain.duel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable database record representing a duel and its participants per ARCHITECTURE.md §4.
 */
public record DuelRecord(
        String id,
        DuelState state,
        Instant createdAt,
        Instant endedAt,
        List<DuelParticipant> participants
) {
    public DuelRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(participants, "participants must not be null");
        participants = List.copyOf(participants);
    }

    public DuelRecord(
            String id,
            DuelState state,
            Instant createdAt,
            List<DuelParticipant> participants
    ) {
        this(id, state, createdAt, null, participants);
    }
}
