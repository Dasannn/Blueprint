package com.dasannn.socialblueprint.feature.honor;

import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a pending player honor action awaiting confirmation per SB-052.
 * The cost is calculated, shown, and confirmed before money moves or events are written.
 */
public record PendingConfirmation(
        PlayerId actorId,
        PlayerId targetId,
        String targetName,
        HonorKind kind,
        int delta,
        double cost,
        String reason,
        Instant expiresAt
) {
    public PendingConfirmation {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
