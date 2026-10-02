package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.SingleEffectConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import com.dasannn.socialblueprint.config.SerenityEffectsConfig;
import com.dasannn.socialblueprint.domain.PsychosisLevel;

/**
 * Tracks in-memory rate limiting and per-session caps for an active player session
 * per SB-043.
 */
public class PlayerEffectState {

    private long nextEpisodeMillis;
    private PsychosisLevel direction = PsychosisLevel.NEUTRAL;

    public synchronized boolean changeDirection(PsychosisLevel level) {
        PsychosisLevel next = level.hasMadnessEffects() ? PsychosisLevel.HIGH
                : level == PsychosisLevel.SERENITY ? level : PsychosisLevel.NEUTRAL;
        boolean changed = direction != next;
        direction = next;
        return changed;
    }

    private final Map<String, Long> sereneLastFired = new java.util.HashMap<>();
    private final Map<String, Integer> sereneCounts = new java.util.HashMap<>();

    public synchronized boolean canFireSerene(String effect, SerenityEffectsConfig.Rule rule, long now) {
        Long last = sereneLastFired.get(effect);
        return sereneCounts.getOrDefault(effect, 0) < rule.sessionCap()
                && (last == null || now >= last && now - last >= rule.cooldownTicks() * 50L);
    }

    public synchronized void recordSerene(String effect, long now, long reservationTicks) {
        sereneLastFired.put(effect, now);
        sereneCounts.merge(effect, 1, Integer::sum);
        recordEpisode(now, reservationTicks * 50L);
    }

    public synchronized boolean canStartEpisode(long nowMillis) {
        return nowMillis >= nextEpisodeMillis;
    }

    public synchronized void recordEpisode(long nowMillis, long durationMillis) {
        nextEpisodeMillis = nowMillis > Long.MAX_VALUE - durationMillis ? Long.MAX_VALUE : nowMillis + durationMillis;
    }

    private final Map<AmbientEffectType, Long> lastFiredMillis = new EnumMap<>(AmbientEffectType.class);
    private final Map<AmbientEffectType, Integer> sessionCounts = new EnumMap<>(AmbientEffectType.class);

    public synchronized boolean canFire(AmbientEffectType type, SingleEffectConfig config, long nowMillis) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (!config.enabled()) return false;
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
