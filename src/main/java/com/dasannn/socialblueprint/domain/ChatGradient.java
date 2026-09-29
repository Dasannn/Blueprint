package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/**
 * Calculates chat text color gradient based on reputation tier per SB-020 and T-041.
 * - Near-black #202020 at the bottom (Tier.CRIMINAL) to bright white #FFFFFF at the top (Tier.ILUSTRE).
 * - Never absolute black (#000000).
 * - Grayscale gradient with strictly monotonically increasing brightness across the 9 tiers.
 */
public final class ChatGradient {

    private static final int MIN_CHANNEL = 0x20; // 32 in decimal -> #202020
    private static final int MAX_CHANNEL = 0xFF; // 255 in decimal -> #FFFFFF
    private static final int STEPS = 8; // 9 tiers -> 8 intervals

    private ChatGradient() {
    }

    /**
     * Returns the 24-bit RGB integer (0xRRGGBB) for the given Tier.
     */
    public static int rgb(Tier tier) {
        Objects.requireNonNull(tier, "Tier must not be null");
        return rgbByLevel(tier.level());
    }

    /**
     * Returns the 24-bit RGB integer (0xRRGGBB) for a tier level (-4 to +4).
     */
    public static int rgbByLevel(int level) {
        int clamped = Math.max(-4, Math.min(4, level));
        int index = clamped + 4; // maps [-4, 4] to [0, 8]
        int channel = Math.round(MIN_CHANNEL + ((float) (MAX_CHANNEL - MIN_CHANNEL) * index / STEPS));
        return (channel << 16) | (channel << 8) | channel;
    }

    /**
     * Returns the hexadecimal color string "#RRGGBB" for the given Tier.
     */
    public static String hex(Tier tier) {
        int color = rgb(tier);
        return String.format("#%06X", color);
    }
}
