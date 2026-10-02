package com.dasannn.socialblueprint.domain;

/**
 * Killing Psychosis metric level per SB-001, SB-004 and Constitution §2.3.
 * Madness bands describe rolling-window kills; serenity is the same metric's peaceful direction.
 * Newcomers begin NEUTRAL with no credited peaceful play (SB-005, SB-117).
 */
public enum PsychosisLevel {
    SERENITY("Serenity"),
    NEUTRAL("Neutral"),
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

    public boolean hasMadnessEffects() { return this == LOW || this == MEDIUM || this == HIGH || this == EXTREME; }
}
