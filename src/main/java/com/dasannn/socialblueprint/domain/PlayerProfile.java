package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Player profile entity per ARCHITECTURE.md §4 and SB-060.
 * Tracks last known name and per-player effects opt-out (SB-044).
 * Identity is strictly by UUID (PlayerId).
 */
public record PlayerProfile(
        PlayerId id,
        String lastKnownName,
        boolean effectsOptOut,
        Instant createdAt,
        Instant updatedAt
) {
    public PlayerProfile {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(lastKnownName, "lastKnownName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static PlayerProfile create(PlayerId id, String name, Instant now) {
        return new PlayerProfile(id, name, false, now, now);
    }

    public PlayerProfile withName(String newName, Instant now) {
        return new PlayerProfile(id, newName, effectsOptOut, createdAt, now);
    }

    public PlayerProfile withEffectsOptOut(boolean optOut, Instant now) {
        return new PlayerProfile(id, lastKnownName, optOut, createdAt, now);
    }
}
