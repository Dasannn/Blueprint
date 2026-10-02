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
    void waitUsesBothLimitsAndExpiresExactlyWithoutRestoringRevokedAllowance() {
        var actor = PlayerId.of(UUID.randomUUID()); var target = PlayerId.of(UUID.randomUUID());
        var events = new ArrayList<ReputationEvent>();
        for (int i = 0; i < 4; i++) events.add(new ReputationEvent(i + 1, actor, target, 1,
                HonorKind.POSITIVE, 500, "Helpful", baseTime.plusSeconds(i * 10), null, i == 1 ? "Admin" : null));
        var wait = tracker.waitFor(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(30), Duration.ofSeconds(45));
        assertThat(wait.cooldown()).isEqualTo(Duration.ofSeconds(45));
        assertThat(wait.cap()).isEqualTo(Duration.ofSeconds(3580));
        assertThat(wait.remaining()).isEqualTo(wait.cap());
        assertThat(tracker.waitFor(actor, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(30), Duration.ofSeconds(45)).remaining())
                .isEqualTo(Duration.ofSeconds(45));
        assertThat(tracker.waitFor(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(3610), Duration.ofSeconds(45)).remaining()).isZero();
        assertThat(tracker.canIssue(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(3610))).isTrue();
        assertThat(tracker.waitFor(PlayerId.of(UUID.randomUUID()), target, HonorKind.POSITIVE, events, baseTime, Duration.ofHours(1)).remaining()).isZero();
        events.add(new ReputationEvent(actor, target, -1, HonorKind.NEGATIVE, 500, "Future", baseTime.plusSeconds(10000)));
        assertThat(tracker.waitFor(actor, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(3610), Duration.ofSeconds(45)).remaining()).isZero();
    }

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

    @Test
    @DisplayName("Finding 4: Rolling window uses half-open interval (now - window, now]")
    void windowEdgeHalfOpenBoundary() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // Event issued at baseTime
        List<ReputationEvent> events = List.of(
                new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime)
        );

        // At exactly one window (baseTime + 1 hour): now - window == baseTime
        // Event is excluded under (now - window, now], so allowance is fully restored (remaining = 3, count = 0)
        Instant exactOneWindow = baseTime.plus(Duration.ofHours(1));
        assertThat(tracker.countInWindow(actor, target, HonorKind.POSITIVE, events, exactOneWindow)).isZero();
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, exactOneWindow)).isEqualTo(3);

        // At one nanosecond before 1 hour has elapsed: now - window < baseTime
        // Event is still inside the rolling window (count = 1, remaining = 2)
        Instant justBeforeExpiry = exactOneWindow.minusNanos(1);
        assertThat(tracker.countInWindow(actor, target, HonorKind.POSITIVE, events, justBeforeExpiry)).isEqualTo(1);
        assertThat(tracker.remainingAllowance(actor, target, HonorKind.POSITIVE, events, justBeforeExpiry)).isEqualTo(2);

        // An event at one nanosecond after (now - window) is inside the window
        Instant now = baseTime.plus(Duration.ofHours(2));
        List<ReputationEvent> eventJustAfterWindowEdge = List.of(
                new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofHours(1)).plusNanos(1))
        );
        assertThat(tracker.countInWindow(actor, target, HonorKind.POSITIVE, eventJustAfterWindowEdge, now)).isEqualTo(1);
    }

    @Test
    @DisplayName("Finding 5, SB-058: Administrative events are exempt from cap and do not consume allowance")
    void adminActionsDoNotConsumeAllowance() {
        PlayerId admin = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();

        // Admin issues ADMIN_GIVE (+5) and ADMIN_TAKE (-3)
        events.add(new ReputationEvent(admin, target, 5, HonorKind.ADMIN_GIVE, 0.0, "Admin bonus", baseTime));
        events.add(new ReputationEvent(admin, target, -3, HonorKind.ADMIN_TAKE, 0.0, "Admin penalty", baseTime.plusSeconds(5)));
        events.add(new ReputationEvent(admin, target, -2, HonorKind.ADMIN_RESET, 0.0, "Reset", baseTime.plusSeconds(10)));

        // Admin actions do NOT consume allowance for positive or negative ratings
        assertThat(tracker.countInWindow(admin, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(15))).isZero();
        assertThat(tracker.remainingAllowance(admin, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(15))).isEqualTo(3);
        assertThat(tracker.countInWindow(admin, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(15))).isZero();
        assertThat(tracker.remainingAllowance(admin, target, HonorKind.NEGATIVE, events, baseTime.plusSeconds(15))).isEqualTo(3);

        // Three ordinary ratings must all be allowed
        for (int i = 0; i < 3; i++) {
            assertThat(tracker.canIssue(admin, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(20 + i * 5))).isTrue();
            events.add(new ReputationEvent(admin, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(20 + i * 5)));
        }

        // 4th rating is refused
        assertThat(tracker.canIssue(admin, target, HonorKind.POSITIVE, events, baseTime.plusSeconds(40))).isFalse();
    }
}
