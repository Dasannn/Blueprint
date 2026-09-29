package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HonorAllowanceTrackerTest {

    private final HonorAllowanceConfig config = new HonorAllowanceConfig(3, Duration.ofHours(1));
    private final HonorAllowanceTracker tracker = new HonorAllowanceTracker(config);
    private final Instant baseTime = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("T-016, T-020: At most three positive ratings per actor-target pair in rolling window")
    void positiveCapEnforced() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();

        // 1st rating: allowed
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime)).isTrue();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, baseTime)).isEqualTo(3);
        events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));

        // 2nd rating: allowed
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(10))).isTrue();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(10))).isEqualTo(2);
        events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 750.0, null, baseTime.plusSeconds(10)));

        // 3rd rating: allowed
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(20))).isTrue();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(20))).isEqualTo(1);
        events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 1000.0, null, baseTime.plusSeconds(20)));

        // 4th rating: CAP REACHED (0 remaining, cannot issue)
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(30))).isFalse();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(30))).isZero();
    }

    @Test
    @DisplayName("T-016: Positive and negative counts are completely independent")
    void positiveAndNegativeCountsIndependent() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();
        // Actor exhausts positive allowance (3 positive ratings)
        for (int i = 0; i < 3; i++) {
            events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(i * 10)));
        }

        // Positive cap reached
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(40))).isFalse();

        // But negative allowance is completely unaffected (3 remaining)
        assertThat(tracker.canIssue(actor, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(40))).isTrue();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(40))).isEqualTo(3);

        // Issue 3 negative ratings
        for (int i = 0; i < 3; i++) {
            events.add(new ReputationEvent(actor, target, -1, HonorKind.NEGATIVE, 500.0, "Reason " + i, baseTime.plusSeconds(50 + i * 10)));
        }

        // Now negative cap is also reached
        assertThat(tracker.canIssue(actor, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(100))).isFalse();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(100))).isZero();
    }

    @Test
    @DisplayName("T-016: Allowance is per actor-target pair; other actors have independent allowance against same target")
    void otherActorsHaveIndependentAllowance() {
        PlayerId actorA = PlayerId.of(UUID.randomUUID());
        PlayerId actorB = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();
        // Actor A hits the cap of 3 against Target
        for (int i = 0; i < 3; i++) {
            events.add(new ReputationEvent(actorA, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(i * 10)));
        }

        assertThat(tracker.canIssue(actorA, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(40))).isFalse();

        // Actor B against the same Target still has full allowance (3 remaining)
        assertThat(tracker.canIssue(actorB, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(40))).isTrue();
        assertThat(tracker.remainingAllowance(actorB, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(40))).isEqualTo(3);
    }

    @Test
    @DisplayName("T-016, T-020: Window expiring restores the allowance")
    void windowExpiryRestoresAllowance() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();
        // Actor exhausts positive cap at baseTime
        for (int i = 0; i < 3; i++) {
            events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(i * 10)));
        }

        // Cap reached at 30 minutes
        Instant at30Min = baseTime.plus(Duration.ofMinutes(30));
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, at30Min)).isFalse();

        // After 61 minutes (window is 1 hour): all 3 ratings have expired from the rolling window
        Instant at61Min = baseTime.plus(Duration.ofMinutes(61));
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, at61Min)).isTrue();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, at61Min)).isEqualTo(3);
    }
}
