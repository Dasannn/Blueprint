package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Reputation Confidence calculation per SB-003 and T-013.
 * Thresholds and age weighting half-life are configurable inputs, not constants baked into code.
 */
public record ConfidenceConfig(
        double lowThreshold,
        double establishedThreshold,
        double highThreshold,
        Duration halfLife
) {
    public ConfidenceConfig {
        if (lowThreshold <= 0.0) {
            throw new IllegalArgumentException("lowThreshold must be strictly positive (> 0), got " + lowThreshold);
        }
        if (establishedThreshold <= lowThreshold) {
            throw new IllegalArgumentException("establishedThreshold must be greater than lowThreshold, got "
                    + establishedThreshold + " <= " + lowThreshold);
        }
        if (highThreshold <= establishedThreshold) {
            throw new IllegalArgumentException("highThreshold must be greater than establishedThreshold, got "
                    + highThreshold + " <= " + establishedThreshold);
        }
        Objects.requireNonNull(halfLife, "halfLife must not be null");
        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("halfLife must be positive, got " + halfLife);
        }
    }

    /**
     * Default configuration:
     * - Low: 1.0 (requires at least 1 recent distinct actor)
     * - Established: 5.0 (requires ~5 distinct actors)
     * - High: 15.0 (requires ~15 distinct actors)
     * - Half-life: 30 days
     */
    public static ConfidenceConfig defaults() {
        return new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30));
    }
}
