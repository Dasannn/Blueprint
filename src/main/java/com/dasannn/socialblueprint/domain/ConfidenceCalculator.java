package com.dasannn.socialblueprint.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates Reputation Confidence per SB-003 and T-013.
 * - Computed from the count of distinct actors, weighted by rating age.
 * - Repeated ratings from one actor do not raise Confidence.
 * - Legacy imports and events without actors contribute zero Confidence.
 * - Time-dependent logic takes an injectable clock or instant.
 */
public final class ConfidenceCalculator {

    private final ConfidenceConfig config;

    public ConfidenceCalculator(ConfidenceConfig config) {
        this.config = Objects.requireNonNull(config, "ConfidenceConfig must not be null");
    }

    public ConfidenceConfig config() {
        return config;
    }

    public ConfidenceLevel calculate(Collection<ReputationEvent> events, Clock clock) {
        Objects.requireNonNull(clock, "Clock must not be null");
        return calculate(events, clock.instant());
    }

    public ConfidenceLevel calculate(Collection<ReputationEvent> events, Instant now) {
        double score = calculateScore(events, now);
        if (score < config.lowThreshold()) {
            return ConfidenceLevel.UNKNOWN;
        } else if (score < config.establishedThreshold()) {
            return ConfidenceLevel.LOW;
        } else if (score < config.highThreshold()) {
            return ConfidenceLevel.ESTABLISHED;
        } else {
            return ConfidenceLevel.HIGH;
        }
    }

    /**
     * Calculates the aggregate weighted score from distinct actors.
     * For each distinct actor who has rated this player, only their most recent rating's
     * age weight is counted (max weight 1.0 per distinct actor). Repeated ratings from the same
     * actor cannot raise the actor's contribution above 1.0 or count as multiple actors.
     */
    public double calculateScore(Collection<ReputationEvent> events, Instant now) {
        Objects.requireNonNull(now, "Instant 'now' must not be null");
        if (events == null || events.isEmpty()) {
            return 0.0;
        }

        // Map distinct actors to their most recent rating timestamp
        Map<PlayerId, Instant> mostRecentByActor = new HashMap<>();
        for (ReputationEvent event : events) {
            if (event == null) continue;
            // Legacy imports, admin events, or events without an actor contribute nothing (SB-003, T-091)
            if (event.actor() == null || !event.kind().contributesToConfidence()) {
                continue;
            }

            PlayerId actor = event.actor();
            Instant eventTime = event.createdAt();
            Instant existing = mostRecentByActor.get(actor);
            if (existing == null || eventTime.isAfter(existing)) {
                mostRecentByActor.put(actor, eventTime);
            }
        }

        if (mostRecentByActor.isEmpty()) {
            return 0.0;
        }

        double halfLifeNanos = (double) config.halfLife().toNanos();
        if (halfLifeNanos <= 0.0) {
            throw new IllegalStateException("Half-life duration must be positive in nanoseconds: " + config.halfLife());
        }

        double totalWeightedScore = 0.0;
        for (Instant eventTime : mostRecentByActor.values()) {
            double ageNanos = Math.max(0.0, (double) Duration.between(eventTime, now).toNanos());
            // Exponential decay: weight = 2^(-age / halfLife)
            double weight = Math.pow(2.0, -(ageNanos / halfLifeNanos));
            totalWeightedScore += weight;
        }

        return totalWeightedScore;
    }

    /**
     * Counts the number of distinct contributing actors in the event collection.
     */
    public int countDistinctActors(Collection<ReputationEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Map<PlayerId, Boolean> distinct = new HashMap<>();
        for (ReputationEvent event : events) {
            if (event != null && event.actor() != null && event.kind().contributesToConfidence()) {
                distinct.put(event.actor(), Boolean.TRUE);
            }
        }
        return distinct.size();
    }
}
