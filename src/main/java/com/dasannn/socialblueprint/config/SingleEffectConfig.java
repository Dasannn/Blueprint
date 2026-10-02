package com.dasannn.socialblueprint.config;

import java.time.Duration;
import java.util.Objects;

/** Independent cooldown and session cap for an ambient effect (SB-043). */
public record SingleEffectConfig(Duration cooldown, int sessionCap, boolean enabled) {
    public SingleEffectConfig(Duration cooldown, int sessionCap) { this(cooldown, sessionCap, true); }

    public SingleEffectConfig {
        Objects.requireNonNull(cooldown, "cooldown must not be null");
    }

    public static SingleEffectConfig of(Duration cooldown, int sessionCap) {
        return new SingleEffectConfig(cooldown, sessionCap);
    }
}
