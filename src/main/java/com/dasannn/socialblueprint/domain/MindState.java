package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/** SB-132: pure signed arithmetic, with no spill across Neutral. */
public final class MindState {
    private MindState() {}
    public record Result(double before, double requestedDelta, double appliedDelta, double after, boolean enabled) {}

    public static Result apply(double current, MindInput inputKind, MindInputConfig config) {
        requireValue(current);
        Objects.requireNonNull(inputKind);
        Objects.requireNonNull(config);
        if (!config.enabled()) return new Result(current, 0, 0, current, false);
        double requested = inputKind.bad() ? -(current > 0 ? config.sereneAmount() : config.psychosisAmount())
                : (current < 0 ? config.psychosisAmount() : config.sereneAmount());
        double after = current + requested;
        if (current > 0 && inputKind.bad()) after = Math.max(0, after);
        if (current < 0 && !inputKind.bad()) after = Math.min(0, after);
        after = Math.max(-100, Math.min(100, after));
        return new Result(current, requested, after - current, after, true);
    }

    public static void requireValue(double value) {
        if (!Double.isFinite(value) || value < -100 || value > 100)
            throw new IllegalArgumentException("Mind value must be finite and in [-100, 100]");
    }
}
