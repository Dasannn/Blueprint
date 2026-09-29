package com.dasannn.socialblueprint.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a player, wrapping a {@link UUID}.
 * Per SB-060, UUID is the only durable identity.
 */
public record PlayerId(UUID value) implements Comparable<PlayerId> {

    public PlayerId {
        Objects.requireNonNull(value, "UUID must not be null");
    }

    public static PlayerId of(UUID uuid) {
        return new PlayerId(uuid);
    }

    public static PlayerId fromString(String uuidString) {
        Objects.requireNonNull(uuidString, "UUID string must not be null");
        return new PlayerId(UUID.fromString(uuidString));
    }

    public UUID uuid() {
        return value;
    }

    @Override
    public int compareTo(PlayerId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
