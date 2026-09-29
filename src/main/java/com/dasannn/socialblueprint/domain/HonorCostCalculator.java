package com.dasannn.socialblueprint.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Calculates the cost of issuing an honor rating per SB-050, T-015 and Decision 0001.
 * Cost = fixed base cost * progressive multiplier based on how many ratings the actor has issued
 * inside the rolling window.
 */
public final class HonorCostCalculator {

    private final HonorCostConfig config;

    public HonorCostCalculator(HonorCostConfig config) {
        this.config = Objects.requireNonNull(config, "HonorCostConfig must not be null");
    }

    public HonorCostConfig config() {
        return config;
    }

    /**
     * Progressive multiplier for a given count of ratings issued in the window.
     */
    public double multiplierForRatings(int ratingsInWindow) {
        if (ratingsInWindow < 0) {
            throw new IllegalArgumentException("ratingsInWindow cannot be negative, got " + ratingsInWindow);
        }
        int index = Math.min(ratingsInWindow, config.multipliers().size() - 1);
        return config.multipliers().get(index);
    }

    /**
     * Calculates the exact honor cost for a given count of ratings issued in the window.
     */
    public double calculateCost(int ratingsInWindow) {
        return config.baseCost() * multiplierForRatings(ratingsInWindow);
    }

    public double calculateCost(PlayerId actor, Collection<ReputationEvent> events, Clock clock) {
        Objects.requireNonNull(clock, "Clock must not be null");
        return calculateCost(actor, events, clock.instant());
    }

    /**
     * Calculates the honor cost for an actor by counting their issued ratings in the rolling window.
     */
    public double calculateCost(PlayerId actor, Collection<ReputationEvent> events, Instant now) {
        int count = countActorRatingsInWindow(actor, events, now);
        return calculateCost(count);
    }

    /**
     * Counts how many ratings the actor has issued inside [now - window, now].
     * Only player-issued honor events (POSITIVE/NEGATIVE) by the actor are counted.
     */
    public int countActorRatingsInWindow(PlayerId actor, Collection<ReputationEvent> events, Instant now) {
        Objects.requireNonNull(actor, "Actor PlayerId must not be null");
        Objects.requireNonNull(now, "Instant 'now' must not be null");

        if (events == null || events.isEmpty()) {
            return 0;
        }

        Instant windowStart = now.minus(config.window());
        int count = 0;
        for (ReputationEvent event : events) {
            if (event == null) continue;
            if (actor.equals(event.actor())
                    && event.kind().isPlayerHonor()
                    && !event.createdAt().isBefore(windowStart)
                    && !event.createdAt().isAfter(now)) {
                count++;
            }
        }
        return count;
    }
}
