package com.dasannn.socialblueprint.domain;

/**
 * Killing Psychosis metric level per SB-001, SB-004 and Constitution §2.3.
 * Represents PvP frequency / aggressiveness over a rolling window.
 * LOW is the lowest level (and default for newcomers with no record, SB-005).
 */
public enum PsychosisLevel {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    EXTREME("Extreme");

    private final String displayName;

    PsychosisLevel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
