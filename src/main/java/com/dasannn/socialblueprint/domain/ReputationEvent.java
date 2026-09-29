package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable reputation event per Constitution §2.5 and ARCHITECTURE.md §4.
 * Tracks every reputation delta with actor, target, delta, kind, cost, reason and timestamp.
 */
public record ReputationEvent(
        long id,
        PlayerId actor,
        PlayerId target,
        int delta,
        HonorKind kind,
        double cost,
        String reason,
        Instant createdAt
) {
    public ReputationEvent {
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(kind, "Kind must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");

        if (cost < 0.0) {
            throw new IllegalArgumentException("Cost cannot be negative, got " + cost);
        }

        if (actor != null && actor.equals(target)) {
            throw new IllegalArgumentException("Actor cannot rate themselves: " + actor);
        }

        if (kind.isPlayerHonor()) {
            if (actor == null) {
                throw new IllegalArgumentException("Player honor event must have an actor");
            }
            if (kind == HonorKind.POSITIVE && delta <= 0) {
                throw new IllegalArgumentException("Positive honor must have a positive delta, got " + delta);
            }
            if (kind == HonorKind.NEGATIVE) {
                if (delta >= 0) {
                    throw new IllegalArgumentException("Negative honor must have a negative delta, got " + delta);
                }
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("Negative honor requires a written reason (SB-056)");
                }
            }
        }
    }

    public ReputationEvent(
            PlayerId actor,
            PlayerId target,
            int delta,
            HonorKind kind,
            double cost,
            String reason,
            Instant createdAt
    ) {
        this(0L, actor, target, delta, kind, cost, reason, createdAt);
    }

    public Optional<PlayerId> actorOpt() {
        return Optional.ofNullable(actor);
    }

    public Optional<String> reasonOpt() {
        return Optional.ofNullable(reason);
    }
}
