package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.util.Objects;

public record MindEvent(long id, PlayerId player, String kind, String source, double requestedDelta,
                        double appliedDelta, double before, double after, Instant createdAt, PlayerId actor) {
    public MindEvent {
        Objects.requireNonNull(player); Objects.requireNonNull(kind); Objects.requireNonNull(source); Objects.requireNonNull(createdAt);
        MindState.requireValue(before); MindState.requireValue(after);
        if (!Double.isFinite(requestedDelta) || !Double.isFinite(appliedDelta) || appliedDelta != after - before)
            throw new IllegalArgumentException("Invalid mind event deltas");
    }
}
