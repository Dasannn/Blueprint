package com.dasannn.socialblueprint.domain.duel;

import java.util.Locale;
import java.util.Objects;

/**
 * Lifecycle states of a duel per SB-030 and ARCHITECTURE.md §4.
 * Matches the state column in the SQLite duel table.
 */
public enum DuelState {
    PENDING("pending"),
    ACTIVE("active"),
    ENDED("ended"),
    CANCELLED("cancelled");

    private final String dbValue;

    DuelState(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static DuelState fromDbValue(String value) {
        Objects.requireNonNull(value, "Value must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pending" -> PENDING;
            case "active" -> ACTIVE;
            case "ended" -> ENDED;
            case "cancelled", "canceled" -> CANCELLED;
            default -> throw new IllegalArgumentException("Unknown DuelState: " + value);
        };
    }
}
