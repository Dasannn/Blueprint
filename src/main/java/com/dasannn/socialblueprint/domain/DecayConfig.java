package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for social status rating decay per SB-006, Constitution §2.6, and T-110.
 * A reputation event's contribution to social status falls with its age on an exponential
 * half-life curve.
 * This is separate from Confidence's age weighting (SB-003).
 */
public record DecayConfig(
        boolean enabled,
        Duration halfLife,
        double floor,
        Duration cacheTtl
) {
    public static final Duration DEFAULT_HALF_LIFE = Duration.ofDays(30);
    public static final double DEFAULT_FLOOR = 0.0;
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * Maximum supported half-life duration: 100 years, matching ConfidenceConfig.MAX_HALF_LIFE.
     */
    public static final Duration MAX_HALF_LIFE = Duration.ofDays(365 * 100);

    public DecayConfig {
        Objects.requireNonNull(halfLife, "halfLife must not be null");
        Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");

        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("halfLife must be strictly positive (> 0), got " + halfLife);
        }
        if (halfLife.compareTo(MAX_HALF_LIFE) > 0) {
            throw new IllegalArgumentException("halfLife exceeds maximum supported duration of 100 years ("
                    + MAX_HALF_LIFE + "), got " + halfLife);
        }

        if (!Double.isFinite(floor) || floor < 0.0 || floor > 1.0) {
            throw new IllegalArgumentException("floor must be finite and within [0.0, 1.0], got " + floor);
        }

        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be strictly positive (> 0), got " + cacheTtl);
        }
    }

    public DecayConfig(boolean enabled, Duration halfLife, double floor) {
        this(enabled, halfLife, floor, DEFAULT_CACHE_TTL);
    }

    public static DecayConfig defaults() {
        return new DecayConfig(true, DEFAULT_HALF_LIFE, DEFAULT_FLOOR, DEFAULT_CACHE_TTL);
    }

    public static DecayConfig disabled() {
        return new DecayConfig(false, DEFAULT_HALF_LIFE, DEFAULT_FLOOR, DEFAULT_CACHE_TTL);
    }
}
