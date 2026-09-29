package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Audit log entry per SB-058, SB-064 and ARCHITECTURE.md §4.
 * Tracks administrative actions: actor, operation, target, before, after and timestamp.
 */
public record AuditEvent(
        long id,
        PlayerId actor,
        String operation,
        String target,
        String before,
        String after,
        Instant createdAt
) {
    public AuditEvent {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public AuditEvent(
            PlayerId actor,
            String operation,
            String target,
            String before,
            String after,
            Instant createdAt
    ) {
        this(0L, actor, operation, target, before, after, createdAt);
    }

    public AuditEvent(
            PlayerId actor,
            String operation,
            PlayerId targetPlayer,
            String before,
            String after,
            Instant createdAt
    ) {
        this(0L, actor, operation, Objects.requireNonNull(targetPlayer, "targetPlayer must not be null").toString(), before, after, createdAt);
    }
}
