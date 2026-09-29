package com.dasannn.socialblueprint.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a player, wrapping a {@link UUID}.
 * Per SB-060, UUID is the only durable identity.
 */
public record PlayerId(UUID value) implements Comparable<PlayerId> {

    public static final PlayerId CONSOLE = new PlayerId(new UUID(0L, 0L));

    public PlayerId {
        Objects.requireNonNull(value, "UUID must not be null");
    }

    public static PlayerId of(UUID uuid) {
        Objects.requireNonNull(uuid, "UUID must not be null");
        return uuid.equals(CONSOLE.value()) ? CONSOLE : new PlayerId(uuid);
    }

    public static PlayerId fromString(String uuidString) {
        Objects.requireNonNull(uuidString, "UUID string must not be null");
        String trimmed = uuidString.trim();
        if ("CONSOLE".equalsIgnoreCase(trimmed)) {
            return CONSOLE;
        }
        UUID uuid = UUID.fromString(trimmed);
        return uuid.equals(CONSOLE.value()) ? CONSOLE : new PlayerId(uuid);
    }

    public boolean isConsole() {
        return this.equals(CONSOLE);
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
        return isConsole() ? "CONSOLE" : value.toString();
    }
}
