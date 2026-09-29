package com.dasannn.socialblueprint.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Calculates Killing Psychosis level over a rolling time window per SB-004, SB-031, SB-032, and T-014.
 *
 * NOTE: Per Constitution §2.3 and T-014, this class has NO knowledge of, access to, or path to
 * social status or reputation events. It operates exclusively on {@link PsychosisEvent}s.
 */
public final class PsychosisCalculator {

    private final PsychosisConfig config;

    public PsychosisCalculator(PsychosisConfig config) {
        this.config = Objects.requireNonNull(config, "PsychosisConfig must not be null");
    }

    public PsychosisConfig config() {
        return config;
    }

    public PsychosisLevel calculate(PlayerId player, Collection<PsychosisEvent> events, Clock clock) {
        Objects.requireNonNull(clock, "Clock must not be null");
        return calculate(player, events, clock.instant());
    }

    public PsychosisLevel calculate(PlayerId player, Collection<PsychosisEvent> events, Instant now) {
        int kills = countQualifyingKills(player, events, now);
        if (kills < config.mediumThreshold()) {
            return PsychosisLevel.LOW; // Default lowest level (SB-005)
        } else if (kills < config.highThreshold()) {
            return PsychosisLevel.MEDIUM;
        } else if (kills < config.extremeThreshold()) {
            return PsychosisLevel.HIGH;
        } else {
            return PsychosisLevel.EXTREME;
        }
    }

    /**
     * Counts the qualifying PvP kills for the player in the rolling window (now - window, now].
     * Only open-world kills (CombatContext.OPEN) are counted.
     * Consensual duel kills (CombatContext.DUEL) are excluded per SB-031.
     */
    public int countQualifyingKills(PlayerId player, Collection<PsychosisEvent> events, Instant now) {
        Objects.requireNonNull(player, "PlayerId must not be null");
        Objects.requireNonNull(now, "Instant 'now' must not be null");

        if (events == null || events.isEmpty()) {
            return 0;
        }

        Instant windowStart = now.minus(config.window());
        int count = 0;
        for (PsychosisEvent event : events) {
            if (event == null) continue;
            if (event.killer().equals(player)
                    && event.context() == CombatContext.OPEN
                    && event.createdAt().isAfter(windowStart)
                    && !event.createdAt().isAfter(now)) {
                count++;
            }
        }
        return count;
    }
}
