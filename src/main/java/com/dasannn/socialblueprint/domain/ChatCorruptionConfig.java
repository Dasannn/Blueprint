package com.dasannn.socialblueprint.domain;

/** Rates are percentages of all messages; every other message is protected. */
public record ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int mediumExtent, int highExtent, int extremeExtent, int minLetters) {
    public static final ChatCorruptionConfig DEFAULT = new ChatCorruptionConfig(10, 25, 40, 20, 35, 50, 6);

    public ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int extent) {
        this(mediumRate, highRate, extremeRate, extent, extent, extent, 6);
    }

    public ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int extent, int minLetters) {
        this(mediumRate, highRate, extremeRate, extent, extent, extent, minLetters);
    }

    public int extent(PsychosisLevel level) {
        return switch (level) {
            case MEDIUM -> mediumExtent;
            case HIGH -> highExtent;
            case EXTREME -> extremeExtent;
            default -> 0;
        };
    }

    public ChatCorruptionConfig {
        if (minLetters < 1) throw new IllegalArgumentException("Minimum letters must be positive");
        if (mediumRate < 1 || mediumRate >= highRate || highRate >= extremeRate || extremeRate > 50) {
            throw new IllegalArgumentException("Rates must satisfy 1 <= medium < high < extreme <= 50");
        }
        if (mediumExtent < 1 || mediumExtent > highExtent || highExtent > extremeExtent || extremeExtent > 50) {
            throw new IllegalArgumentException("Extents must satisfy 1 <= medium <= high <= extreme <= 50");
        }
    }
}
