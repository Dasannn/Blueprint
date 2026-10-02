package com.dasannn.socialblueprint.domain;

/** Legacy R1 curve for migration only; idle timeout remains usable for active-play accounting. */
public record SerenityConfig(double ceiling, double activeHoursToCeiling, double idleTimeoutSeconds) {
    public static final SerenityConfig DEFAULT = new SerenityConfig(100, 100, 300);

    public SerenityConfig {
        if (!positive(ceiling) || !positive(activeHoursToCeiling) || !positive(idleTimeoutSeconds)
                || !Double.isFinite(activeHoursToCeiling * 3_600_000)
                || !Double.isFinite(idleTimeoutSeconds * 1000)) {
            throw new IllegalArgumentException("Serenity values must be positive and finite");
        }
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    public double clamp(double millis) { return Math.min(Math.max(0, millis), activeHoursToCeiling * 3_600_000); }
    public double magnitude(double millis) {
        double x = clamp(millis) / (activeHoursToCeiling * 3_600_000);
        return ceiling * (2 * x - x * x);
    }
}
