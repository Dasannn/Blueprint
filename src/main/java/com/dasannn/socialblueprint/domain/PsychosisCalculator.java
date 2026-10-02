package com.dasannn.socialblueprint.domain;

import java.util.Objects;

public final class PsychosisCalculator {
    private final PsychosisConfig config;
    public PsychosisCalculator(PsychosisConfig config) { this.config = Objects.requireNonNull(config); }
    public PsychosisConfig config() { return config; }
    public PsychosisLevel calculate(double value) {
        MindState.requireValue(value);
        if (value > 0) return PsychosisLevel.SERENITY;
        if (value == 0) return PsychosisLevel.NEUTRAL;
        double p = -value;
        if (p >= config.extremeThreshold()) return PsychosisLevel.EXTREME;
        if (p >= config.highThreshold()) return PsychosisLevel.HIGH;
        if (p >= config.mediumThreshold()) return PsychosisLevel.MEDIUM;
        return PsychosisLevel.LOW;
    }
}
