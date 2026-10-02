package com.dasannn.socialblueprint.domain;

/** Rates are percentages of all messages; every other message is protected. */
public record ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int extent, int minLetters) {
    public static final ChatCorruptionConfig DEFAULT = new ChatCorruptionConfig(10, 25, 40, 20, 6);

    public ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int extent) {
        this(mediumRate, highRate, extremeRate, extent, 6);
    }

    public ChatCorruptionConfig {
        if (minLetters < 1) throw new IllegalArgumentException("Minimum letters must be positive");
        if (mediumRate < 1 || mediumRate >= highRate || highRate >= extremeRate || extremeRate > 50) {
            throw new IllegalArgumentException("Rates must satisfy 1 <= medium < high < extreme <= 50");
        }
        if (extent < 1 || extent > 25) {
            throw new IllegalArgumentException("Extent must be between 1 and 25 percent");
        }
    }
}
