package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Player profile entity per ARCHITECTURE.md §4 and SB-060.
 * Tracks last known name (SB-060).
 * Identity is strictly by UUID (PlayerId).
 */
public record PlayerProfile(
        PlayerId id,
        String lastKnownName,
        Instant createdAt,
        Instant updatedAt,
        double mindValue
) {
    public PlayerProfile(PlayerId id, String lastKnownName, Instant createdAt, Instant updatedAt) {
        this(id, lastKnownName, createdAt, updatedAt, 0);
    }

    public PlayerProfile {
        MindState.requireValue(mindValue);
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(lastKnownName, "lastKnownName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static PlayerProfile create(PlayerId id, String name, Instant now) {
        return new PlayerProfile(id, name, now, now);
    }

    public PlayerProfile withName(String newName, Instant now) {
        return new PlayerProfile(id, newName, createdAt, now, mindValue);
    }

}
