package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the per-pair honor allowance per SB-054, T-016 and Decision 0001.
 */
public record HonorAllowanceConfig(
        int maxPerTarget,
        Duration window
) {
    public HonorAllowanceConfig {
        if (maxPerTarget <= 0) {
            throw new IllegalArgumentException("maxPerTarget must be strictly positive (> 0), got " + maxPerTarget);
        }
        Objects.requireNonNull(window, "Window duration must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Window duration must be strictly positive, got " + window);
        }
    }

    public static HonorAllowanceConfig defaults() {
        return new HonorAllowanceConfig(3, Duration.ofHours(1));
    }
}
