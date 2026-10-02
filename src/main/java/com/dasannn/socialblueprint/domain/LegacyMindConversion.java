package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.Objects;

/** Release-1 settings are used only by migration 6. */
public record LegacyMindConversion(Duration window, SerenityConfig serenity) {
    public static final LegacyMindConversion DEFAULT = new LegacyMindConversion(Duration.ofHours(72), SerenityConfig.DEFAULT);
    public LegacyMindConversion {
        Objects.requireNonNull(window);
        Objects.requireNonNull(serenity);
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("Legacy window must be positive");
    }
    public double convert(int eligibleKills, double activeMillis) {
        if (eligibleKills < 0 || !Double.isFinite(activeMillis) || activeMillis < 0)
            throw new IllegalArgumentException("Invalid release-1 data");
        return eligibleKills > 0 ? -Math.min(100, 10d * eligibleKills)
                : Math.min(100, serenity.magnitude(activeMillis));
    }
}
