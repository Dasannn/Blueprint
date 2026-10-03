package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/** Quotes the actor's balance once; confirmation charges the stored quote. */
public final class HonorCostCalculator {
    private final HonorCostConfig config;
    public HonorCostCalculator(HonorCostConfig config) { this.config = Objects.requireNonNull(config); }
    public HonorCostConfig config() { return config; }
    public static double roundCurrency(double amount) {
        return java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
    public double calculateCost(double actorBalance) {
        if (!Double.isFinite(actorBalance)) throw new IllegalArgumentException("Balance must be finite");
        double cost = config.baseCost() + config.percent() / 100 * Math.max(0, actorBalance);
        if (!Double.isFinite(cost)) throw new ArithmeticException("Calculated honor cost overflowed");
        return roundCurrency(cost);
    }
}
