package com.dasannn.socialblueprint.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Tracks and enforces per-pair honor allowances per SB-054, T-016 and Decision 0001:
 * - At most three positive and three negative from one actor to one target inside the rolling window.
 * - Positive and negative counts are completely independent.
 * - The cap is strictly per actor-target pair: other actors have their own allowance against the same target.
 * - The rolling window expiring restores the allowance.
 */
public final class HonorAllowanceTracker {

    private final HonorAllowanceConfig config;

    public HonorAllowanceTracker(HonorAllowanceConfig config) {
        this.config = Objects.requireNonNull(config, "HonorAllowanceConfig must not be null");
    }

    public HonorAllowanceConfig config() {
        return config;
    }

    public boolean canIssue(PlayerId actor, PlayerId target, HonorKind kind, Collection<ReputationEvent> events, Clock clock) {
        Objects.requireNonNull(clock, "Clock must not be null");
        return canIssue(actor, target, kind, events, clock.instant());
    }

    /**
     * Checks if the actor is permitted to issue another rating of the given kind to the target.
     */
    public boolean canIssue(PlayerId actor, PlayerId target, HonorKind kind, Collection<ReputationEvent> events, Instant now) {
        return remainingAllowance(actor, target, kind, events, now) > 0;
    }

    /**
     * Returns remaining allowance for this actor-target pair and kind in the window.
     */
    public int remainingAllowance(PlayerId actor, PlayerId target, HonorKind kind, Collection<ReputationEvent> events, Instant now) {
        int used = countInWindow(actor, target, kind, events, now);
        return Math.max(0, config.maxPerTarget() - used);
    }

    /**
     * Counts how many ratings of the given kind (or sign) the actor has issued to the target
     * within (now - window, now].
     */
    public int countInWindow(PlayerId actor, PlayerId target, HonorKind kind, Collection<ReputationEvent> events, Instant now) {
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(kind, "HonorKind must not be null");
        Objects.requireNonNull(now, "Instant 'now' must not be null");

        if (actor.equals(target)) {
            return config.maxPerTarget(); // Cannot rate oneself
        }

        if (events == null || events.isEmpty()) {
            return 0;
        }

        Instant windowStart = now.minus(config.window());
        int count = 0;
        for (ReputationEvent event : events) {
            if (event == null) continue;
            // Match actor, target, sign/kind, and within window
            if (actor.equals(event.actor())
                    && target.equals(event.target())
                    && matchesSign(kind, event.kind())
                    && event.createdAt().isAfter(windowStart)
                    && !event.createdAt().isAfter(now)) {
                count++;
            }
        }
        return count;
    }

    private static boolean matchesSign(HonorKind requested, HonorKind eventKind) {
        if (requested == HonorKind.POSITIVE && eventKind == HonorKind.POSITIVE) {
            return true;
        }
        return requested == HonorKind.NEGATIVE && eventKind == HonorKind.NEGATIVE;
    }
}
