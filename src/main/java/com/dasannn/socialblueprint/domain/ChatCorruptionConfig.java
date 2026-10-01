package com.dasannn.socialblueprint.domain;

/** Rates are percentages of all messages; every other message is protected. */
public record ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int extent) {
    public static final ChatCorruptionConfig DEFAULT = new ChatCorruptionConfig(10, 25, 40, 20);

    public ChatCorruptionConfig {
        if (mediumRate < 1 || mediumRate >= highRate || highRate >= extremeRate || extremeRate > 50) {
            throw new IllegalArgumentException("Rates must satisfy 1 <= medium < high < extreme <= 50");
        }
        if (extent < 1 || extent > 25) {
            throw new IllegalArgumentException("Extent must be between 1 and 25 percent");
        }
    }
}
