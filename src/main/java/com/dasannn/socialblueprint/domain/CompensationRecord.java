package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an outstanding refund compensation per SB-057.
 * Persisted when funds are withdrawn so a refund survives crashes/restarts
 * if the reputation event write fails or deposit cannot complete immediately.
 */
public record CompensationRecord(
        long id,
        UUID playerUuid,
        double amount,
        String reason,
        Instant createdAt
) {
    public CompensationRecord {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amount <= 0.0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite and positive: " + amount);
        }
    }
}
