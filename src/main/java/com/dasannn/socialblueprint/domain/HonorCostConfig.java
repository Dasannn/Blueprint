package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for Honor cost calculation per SB-050, T-015 and Decision 0001.
 */
public record HonorCostConfig(
        double baseCost,
        List<Double> multipliers,
        Duration window
) {
    public HonorCostConfig {
        if (!Double.isFinite(baseCost) || baseCost <= 0.0) {
            throw new IllegalArgumentException("Base cost must be finite and strictly positive (> 0), got " + baseCost);
        }
        Objects.requireNonNull(multipliers, "Multipliers list must not be null");
        if (multipliers.isEmpty()) {
            throw new IllegalArgumentException("Multipliers list must not be empty");
        }
        for (int i = 0; i < multipliers.size(); i++) {
            Double m = multipliers.get(i);
            if (m == null || !Double.isFinite(m) || m <= 0.0) {
                throw new IllegalArgumentException("Multiplier at index " + i + " must be finite and strictly positive (> 0), got " + m);
            }
            if (i > 0 && m < multipliers.get(i - 1)) {
                throw new IllegalArgumentException("Multipliers must be non-decreasing: index " + i + " (" + m
                        + ") < index " + (i - 1) + " (" + multipliers.get(i - 1) + ")");
            }
        }
        Objects.requireNonNull(window, "Window duration must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Window duration must be strictly positive, got " + window);
        }
        multipliers = List.copyOf(multipliers);
    }

    public static HonorCostConfig defaults() {
        return new HonorCostConfig(500.0, List.of(1.0, 1.5, 2.0, 3.0), Duration.ofHours(1));
    }
}
