package com.dasannn.socialblueprint.domain;

/** Rates are percentages of all messages; every other message is protected. */
public record ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int mediumExtent, int highExtent, int extremeExtent, int minLetters,
                                   String mediumColour, String highColour, String extremeColour, boolean enabled) {
    public static final ChatCorruptionConfig DEFAULT = new ChatCorruptionConfig(10, 25, 40, 20, 35, 50, 6);

    public ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int mediumExtent,
                                int highExtent, int extremeExtent, int minLetters,
                                String mediumColour, String highColour, String extremeColour) {
        this(mediumRate, highRate, extremeRate, mediumExtent, highExtent, extremeExtent, minLetters,
                mediumColour, highColour, extremeColour, true);
    }

    public ChatCorruptionConfig(int mediumRate, int highRate, int extremeRate, int mediumExtent,
                                int highExtent, int extremeExtent, int minLetters) {
        this(mediumRate, highRate, extremeRate, mediumExtent, highExtent, extremeExtent, minLetters,
                "#AAAAAA", "#666666", "#303030");
    }

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

    public String colour(PsychosisLevel level) {
        return switch (level) {
            case MEDIUM -> mediumColour;
            case HIGH -> highColour;
            case EXTREME -> extremeColour;
            default -> null;
        };
    }

    // Relative sRGB luminance; the shipped #303030 remains above the readability floor.
    private static double luminance(String colour, String key) {
        if (colour == null || !colour.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException(key + " must be #RRGGBB");
        int rgb = Integer.parseInt(colour.substring(1), 16);
        double result = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double channel = ((rgb >> (16 - 8 * i)) & 255) / 255.0;
            result += weights[i] * (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        if (result < 0.025) throw new IllegalArgumentException(key + " luminance must be >= 0.025");
        return result;
    }

    public ChatCorruptionConfig {
        double mediumLight = luminance(mediumColour, "medium-colour");
        double highLight = luminance(highColour, "high-colour");
        double extremeLight = luminance(extremeColour, "extreme-colour");
        if (highLight > mediumLight) throw new IllegalArgumentException("high-colour must be no lighter than medium-colour");
        if (extremeLight > highLight) throw new IllegalArgumentException("extreme-colour must be no lighter than high-colour");
        if (minLetters < 1) throw new IllegalArgumentException("Minimum letters must be positive");
        if (mediumRate < 1 || mediumRate >= highRate || highRate >= extremeRate || extremeRate > 50) {
            throw new IllegalArgumentException("Rates must satisfy 1 <= medium < high < extreme <= 50");
        }
        if (mediumExtent < 1 || mediumExtent > highExtent || highExtent > extremeExtent || extremeExtent > 50) {
            throw new IllegalArgumentException("Extents must satisfy 1 <= medium <= high <= extreme <= 50");
        }
    }
}
