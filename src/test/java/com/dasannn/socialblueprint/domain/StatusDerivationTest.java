package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatusDerivationTest {

    @Test
    @DisplayName("T-012, T-020, SB-005: Empty event list derives Status.ZERO")
    void emptyListDerivesZero() {
        assertThat(Status.fromEvents(List.of())).isEqualTo(Status.ZERO);
        assertThat(Status.fromEvents(null)).isEqualTo(Status.ZERO);
    }

    @Test
    @DisplayName("T-012, T-020: Status is purely derived from event deltas")
    void deriveFromEvents() {
        PlayerId actor1 = PlayerId.of(UUID.randomUUID());
        PlayerId actor2 = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        List<ReputationEvent> events = List.of(
                new ReputationEvent(actor1, target, 1, HonorKind.POSITIVE, 500.0, null, now),
                new ReputationEvent(actor2, target, 1, HonorKind.POSITIVE, 500.0, null, now.plusSeconds(10)),
                new ReputationEvent(actor1, target, -1, HonorKind.NEGATIVE, 500.0, "Bad trader", now.plusSeconds(20)),
                new ReputationEvent(actor2, target, 1, HonorKind.POSITIVE, 500.0, null, now.plusSeconds(30))
        );

        Status derived = Status.fromEvents(events);
        assertThat(derived.value()).isEqualTo(2);
        assertThat(derived.isPositive()).isTrue();
    }

    @Test
    @DisplayName("T-012, SB-058: Administrative reset writes a compensating event, returning derived status to 0")
    void administrativeResetCompensates() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        List<ReputationEvent> events = new ArrayList<>();
        events.add(new ReputationEvent(actor, target, 5, HonorKind.POSITIVE, 0.0, "Admin bonus", now));
        events.add(new ReputationEvent(actor, target, 3, HonorKind.POSITIVE, 0.0, "Admin bonus", now.plusSeconds(5)));

        assertThat(Status.fromEvents(events).value()).isEqualTo(8);

        // Admin resets status: writes compensating delta (-8) per SB-058
        events.add(new ReputationEvent(actor, target, -8, HonorKind.ADMIN_RESET, 0.0, "Reset by admin", now.plusSeconds(10)));

        Status afterReset = Status.fromEvents(events);
        assertThat(afterReset.value()).isZero();
        assertThat(afterReset.isNeutral()).isTrue();
    }

    @Test
    @DisplayName("T-012: Legacy import event contributes to derived status")
    void legacyImportContributesToStatus() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        List<ReputationEvent> events = List.of(
                new ReputationEvent(0L, null, target, 42, HonorKind.LEGACY_IMPORT, 0.0, "Imported from PlayerStatus", now)
        );

        Status derived = Status.fromEvents(events);
        assertThat(derived.value()).isEqualTo(42);
    }

    @Test
    @DisplayName("Finding 1: Status overflow throws ArithmeticException instead of wrapping into Criminal")
    void statusOverflowThrowsArithmeticException() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        // Two positive events whose deltas sum beyond Integer.MAX_VALUE
        List<ReputationEvent> positiveOverflowEvents = List.of(
                new ReputationEvent(0L, actor, target, Integer.MAX_VALUE, HonorKind.POSITIVE, 0.0, null, now),
                new ReputationEvent(0L, actor, target, 1, HonorKind.POSITIVE, 0.0, null, now.plusSeconds(1))
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Status.fromEvents(positiveOverflowEvents))
                .isInstanceOf(ArithmeticException.class);

        // Two negative events whose deltas sum below Integer.MIN_VALUE
        List<ReputationEvent> negativeOverflowEvents = List.of(
                new ReputationEvent(0L, actor, target, Integer.MIN_VALUE, HonorKind.NEGATIVE, 0.0, "Negative", now),
                new ReputationEvent(0L, actor, target, -1, HonorKind.NEGATIVE, 0.0, "Negative", now.plusSeconds(1))
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Status.fromEvents(negativeOverflowEvents))
                .isInstanceOf(ArithmeticException.class);

        // Status.plus also checks overflow
        Status max = Status.of(Integer.MAX_VALUE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> max.plus(1))
                .isInstanceOf(ArithmeticException.class);
    }
}
