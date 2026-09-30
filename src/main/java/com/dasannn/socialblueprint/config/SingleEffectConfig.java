package com.dasannn.socialblueprint.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for an individual ambient effect per SB-040, SB-043.
 */
public record SingleEffectConfig(
        Duration cooldown,
        int sessionCap,
        int durationTicks,
        List<String> fakeNames
) {
    public SingleEffectConfig {
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        fakeNames = fakeNames != null ? List.copyOf(fakeNames) : List.of();
    }

    public static SingleEffectConfig of(Duration cooldown, int sessionCap) {
        return new SingleEffectConfig(cooldown, sessionCap, 0, List.of());
    }

    public static SingleEffectConfig of(Duration cooldown, int sessionCap, int durationTicks) {
        return new SingleEffectConfig(cooldown, sessionCap, durationTicks, List.of());
    }

    public static SingleEffectConfig of(Duration cooldown, int sessionCap, List<String> fakeNames) {
        return new SingleEffectConfig(cooldown, sessionCap, 0, fakeNames);
    }
}
