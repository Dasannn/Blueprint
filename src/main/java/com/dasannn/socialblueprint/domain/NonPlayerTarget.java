package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/**
 * A non-player audit target (such as a configuration key or server subsystem) per SB-064.
 * Distinguishes administrative non-player targets from {@link PlayerId}, preventing
 * accidental use of player names as audit targets and avoiding rename splitting (SB-060).
 */
public record NonPlayerTarget(String identifier) {

    public NonPlayerTarget {
        Objects.requireNonNull(identifier, "Target identifier must not be null");
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Target identifier must not be blank");
        }
    }

    public static NonPlayerTarget of(String identifier) {
        return new NonPlayerTarget(identifier);
    }

    public static NonPlayerTarget configKey(String key) {
        return new NonPlayerTarget(key);
    }

    @Override
    public String toString() {
        return identifier;
    }
}
