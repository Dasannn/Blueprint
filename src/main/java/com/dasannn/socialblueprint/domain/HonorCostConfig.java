package com.dasannn.socialblueprint.domain;

/** Balance-based honor pricing (SB-050, decision 0008). */
public record HonorCostConfig(double baseCost, double percent) {
    public HonorCostConfig {
        if (!Double.isFinite(baseCost) || baseCost < 0)
            throw new IllegalArgumentException("Base cost must be finite and non-negative");
        if (!Double.isFinite(percent) || percent < 0 || percent > 100)
            throw new IllegalArgumentException("Cost percent must be in [0, 100]");
        if (baseCost == 0 && percent == 0)
            throw new IllegalArgumentException("Base cost and percent cannot both be zero");
    }
    public static HonorCostConfig defaults() { return new HonorCostConfig(30, 8); }
}
