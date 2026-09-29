package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable combat kill event per ARCHITECTURE.md §4.
 * Tracks killer, victim, combat context and timestamp.
 * Stored and computed completely separately from social status (Constitution §2.3, T-014).
 */
public record PsychosisEvent(
        long id,
        PlayerId killer,
        PlayerId victim,
        CombatContext context,
        Instant createdAt
) {
    public PsychosisEvent {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        Objects.requireNonNull(context, "CombatContext must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");

        if (killer.equals(victim)) {
            throw new IllegalArgumentException("Killer cannot be the victim: " + killer);
        }
    }

    public PsychosisEvent(
            PlayerId killer,
            PlayerId victim,
            CombatContext context,
            Instant createdAt
    ) {
        this(0L, killer, victim, context, createdAt);
    }
}
