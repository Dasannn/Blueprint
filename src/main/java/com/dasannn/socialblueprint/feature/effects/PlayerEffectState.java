package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.SingleEffectConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks in-memory rate limiting and per-session caps for an active player session
 * per SB-043.
 */
public class PlayerEffectState {

    private final Map<AmbientEffectType, Long> lastFiredMillis = new EnumMap<>(AmbientEffectType.class);
    private final Map<AmbientEffectType, Integer> sessionCounts = new EnumMap<>(AmbientEffectType.class);

    public synchronized boolean canFire(AmbientEffectType type, SingleEffectConfig config, long nowMillis) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(config, "config must not be null");

        int count = sessionCounts.getOrDefault(type, 0);
        if (count >= config.sessionCap()) {
            return false;
        }

        long lastFired = lastFiredMillis.getOrDefault(type, 0L);
        long cooldownMs = config.cooldown().toMillis();
        return (nowMillis - lastFired) >= cooldownMs;
    }

    public synchronized void recordFired(AmbientEffectType type, long nowMillis) {
        Objects.requireNonNull(type, "type must not be null");
        lastFiredMillis.put(type, nowMillis);
        sessionCounts.merge(type, 1, Integer::sum);
    }

    public synchronized int getSessionCount(AmbientEffectType type) {
        return sessionCounts.getOrDefault(type, 0);
    }

    public synchronized long getLastFiredMillis(AmbientEffectType type) {
        return lastFiredMillis.getOrDefault(type, 0L);
    }

    public synchronized void resetSessionCounts() {
        sessionCounts.clear();
    }
}
