package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Killing Psychosis rolling window and thresholds per SB-004, SB-005, and T-014.
 */
public record PsychosisConfig(
        Duration window,
        int mediumThreshold,
        int highThreshold,
        int extremeThreshold
) {
    public PsychosisConfig {
        Objects.requireNonNull(window, "Window duration must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Window duration must be strictly positive, got " + window);
        }
        if (mediumThreshold <= 0) {
            throw new IllegalArgumentException("mediumThreshold must be > 0, got " + mediumThreshold);
        }
        if (highThreshold <= mediumThreshold) {
            throw new IllegalArgumentException("highThreshold must be > mediumThreshold, got "
                    + highThreshold + " <= " + mediumThreshold);
        }
        if (extremeThreshold <= highThreshold) {
            throw new IllegalArgumentException("extremeThreshold must be > highThreshold, got "
                    + extremeThreshold + " <= " + highThreshold);
        }
    }

    public static PsychosisConfig defaults() {
        return new PsychosisConfig(Duration.ofHours(24), 2, 5, 10);
    }
}
