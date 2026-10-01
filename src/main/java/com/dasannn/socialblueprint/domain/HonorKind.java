package com.dasannn.socialblueprint.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Kind of reputation event.
 * Per SB-055, players have two honor actions: giving (positive) and removing (negative).
 * Administrative actions and legacy imports are also tracked as immutable reputation events.
 */
public enum HonorKind {
    POSITIVE("positive"),
    NEGATIVE("negative"),
    ADMIN_GIVE("admin_give"),
    ADMIN_TAKE("admin_take"),
    ADMIN_RESET("admin_reset"),
    LEGACY_IMPORT("legacy_import"),
    SYSTEM_KILL("system_kill");

    public static final HonorKind GIVE = POSITIVE;
    public static final HonorKind REMOVE = NEGATIVE;

    private final String dbValue;

    HonorKind(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * Whether this event represents a positive change in reputation.
     */
    public boolean isPositive() {
        return this == POSITIVE || this == ADMIN_GIVE;
    }

    /**
     * Whether this event represents a negative change in reputation.
     */
    public boolean isNegative() {
        return this == NEGATIVE || this == ADMIN_TAKE || this == SYSTEM_KILL;
    }

    /**
     * Whether this event was issued by a regular player spending honor.
     */
    public boolean isPlayerHonor() {
        return this == POSITIVE || this == NEGATIVE;
    }

    /**
     * Whether this event contributes evidence toward Reputation Confidence.
     * Per SB-003 and ARCHITECTURE.md §4, legacy imports, admin resets and system kills contribute no Confidence.
     */
    public boolean contributesToConfidence() {
        return this == POSITIVE || this == NEGATIVE;
    }

    public static HonorKind fromDbValue(String value) {
        Objects.requireNonNull(value, "Value must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "positive", "give", "trust", "+1" -> POSITIVE;
            case "negative", "remove", "distrust", "-1" -> NEGATIVE;
            case "admin_give" -> ADMIN_GIVE;
            case "admin_take", "admin_remove" -> ADMIN_TAKE;
            case "admin_reset", "reset" -> ADMIN_RESET;
            case "legacy_import" -> LEGACY_IMPORT;
            case "system_kill" -> SYSTEM_KILL;
            default -> throw new IllegalArgumentException("Unknown HonorKind: " + value);
        };
    }
}
