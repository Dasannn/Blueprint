package com.dasannn.socialblueprint.domain;

/** SB-131: thresholds are psychosis magnitudes, never kill counts. */
public record PsychosisConfig(double mediumThreshold, double highThreshold, double extremeThreshold) {
    public PsychosisConfig {
        if (!Double.isFinite(mediumThreshold) || !Double.isFinite(highThreshold) || !Double.isFinite(extremeThreshold)
                || mediumThreshold <= 0 || highThreshold <= mediumThreshold
                || extremeThreshold <= highThreshold || extremeThreshold > 100)
            throw new IllegalArgumentException("Psychosis thresholds must strictly increase inside (0, 100]");
    }
    public static PsychosisConfig defaults() { return new PsychosisConfig(20, 50, 80); }
}
