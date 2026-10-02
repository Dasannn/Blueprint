package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorRevocationTest {
    private final PlayerId actor = PlayerId.of(UUID.randomUUID());
    private final PlayerId target = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-02T12:00:00Z");

    @Test void revokedRatingsNeverContributeWithOrWithoutDecay() {
        for (HonorKind kind : List.of(HonorKind.POSITIVE, HonorKind.NEGATIVE, HonorKind.SYSTEM_KILL)) {
            int delta = kind.isPositive() ? 4 : -4;
            ReputationEvent rating = new ReputationEvent(1, kind == HonorKind.SYSTEM_KILL ? null : actor,
                    target, delta, kind, kind.isPlayerHonor() ? 500 : 0, "Original reason", now.minus(Duration.ofDays(7)));
            ReputationEvent other = new ReputationEvent(2, actor, target, 2, HonorKind.POSITIVE, 500, "Helpful", now);
            ReputationEvent revocation = new ReputationEvent(3, actor, target, 0, HonorKind.REVOCATION, 0,
                    "Admin", now, 1L, null);
            List<ReputationEvent> events = List.of(rating, other, revocation);
            assertThat(Status.fromEvents(events).value()).isEqualTo(2);
            assertThat(Status.fromEvents(events, new DecayConfig(true, Duration.ofDays(7), 0, Duration.ofSeconds(60)), now).value()).isEqualTo(2);
            assertThat(rating.reason()).isEqualTo("Original reason");
            assertThat(revocation.canRevoke()).isFalse();
        }
    }

    @Test void annotationExcludesRatingEvenWithoutTheReferenceEventInThePage() {
        ReputationEvent revoked = new ReputationEvent(1, actor, target, 1, HonorKind.POSITIVE, 500,
                "Helpful", now, null, "Admin");
        assertThat(revoked.canRevoke()).isFalse();
        assertThat(Status.fromEvents(List.of(revoked))).isEqualTo(Status.ZERO);
        assertThatThrownBy(() -> new ReputationEvent(2, actor, target, 1, HonorKind.REVOCATION,
                0, "Admin", now, 1L, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
