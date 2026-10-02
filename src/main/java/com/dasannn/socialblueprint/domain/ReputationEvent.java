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
        Instant createdAt,
        Long revokedRatingId,
        String revokedBy
) {
    /**
     * Maximum sane magnitude for a single reputation delta (±10,000).
     * Justification: In SocialBlueprint, tier thresholds span -30 (Criminal) to +50 (Ilustre),
     * a total range of 80 points. A delta of 10,000 is 125 times the entire ladder span and
     * 200 times the highest tier threshold. Bounding delta to ±10,000 prevents corrupted or
     * overflow-inducing rows from entering the database (which would otherwise poison all future
     * reads in Status.fromEvents), while providing generous capacity for any administrative
     * adjustment, reset compensation, or legacy import.
     */
    public static final int MAX_DELTA = 10_000;
    public static final int MAX_REASON_LENGTH = 100;

    public ReputationEvent(long id, PlayerId actor, PlayerId target, int delta,
                           HonorKind kind, double cost, String reason, Instant createdAt) {
        this(id, actor, target, delta, kind, cost, reason, createdAt, null, null);
    }

    public static java.util.List<ReputationEvent> visibleHistory(java.util.List<ReputationEvent> events, boolean canRevoke) {
        return events == null ? java.util.List.of() : events.stream()
                .filter(e -> e.kind() != HonorKind.REVOCATION && (canRevoke || e.revokedBy() == null)).toList();
    }

    public boolean canRevoke() {
        return revokedBy == null && (kind.isPlayerHonor() || kind == HonorKind.SYSTEM_KILL
                || kind == HonorKind.ADMIN_GIVE || kind == HonorKind.ADMIN_TAKE);
    }

    public ReputationEvent {
        if (kind == HonorKind.REVOCATION) {
            if (actor == null || reason == null || reason.isBlank() || revokedRatingId == null || revokedRatingId <= 0 || delta != 0 || cost != 0)
                throw new IllegalArgumentException("A revocation must reference a rating and carry no delta or cost");
        } else if (revokedRatingId != null) {
            throw new IllegalArgumentException("Only a revocation may reference a rating");
        }
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(kind, "Kind must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");

        if (!Double.isFinite(cost) || cost < 0.0) {
            throw new IllegalArgumentException("Cost cannot be negative and must be finite, got " + cost);
        }

        if (kind.isPlayerHonor() && actor != null && actor.equals(target)) {
            throw new IllegalArgumentException("Actor cannot rate themselves: " + actor);
        }

        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason exceeds maximum length (" + MAX_REASON_LENGTH + "), got " + reason.length());
        }

        if (kind.isPlayerHonor()) {
            if (cost <= 0.0) {
                throw new IllegalArgumentException("Player honor event must have a strictly positive cost (> 0) per Constitution §2.4, got " + cost);
            }
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
