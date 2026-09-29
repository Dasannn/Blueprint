package com.dasannn.socialblueprint.domain;

/**
 * Reputation Confidence metric level per SB-001 and SB-003.
 * Indicates statistical evidence supporting a player's social status.
 */
public enum ConfidenceLevel {
    UNKNOWN("Unknown"),
    LOW("Low"),
    ESTABLISHED("Established"),
    HIGH("High");

    private final String displayName;

    ConfidenceLevel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
