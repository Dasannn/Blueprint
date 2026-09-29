package com.dasannn.socialblueprint.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Context of a player kill per SB-031, SB-032 and ARCHITECTURE.md §4.
 * - OPEN: kill in open world (unconsented PvP), raises Killing Psychosis.
 * - DUEL: kill in consensual duel, affects neither social status nor Killing Psychosis.
 */
public enum CombatContext {
    OPEN("open"),
    DUEL("duel");

    private final String dbValue;

    CombatContext(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static CombatContext fromDbValue(String value) {
        Objects.requireNonNull(value, "Value must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "open" -> OPEN;
            case "duel" -> DUEL;
            default -> throw new IllegalArgumentException("Unknown CombatContext: " + value);
        };
    }
}
