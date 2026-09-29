package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceCalculatorTest {

    private final ConfidenceConfig config = new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30));
    private final ConfidenceCalculator calculator = new ConfidenceCalculator(config);
    private final Instant baseTime = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("T-013, SB-005: Empty events list yields UNKNOWN confidence")
    void emptyEventsYieldsUnknown() {
        assertThat(calculator.calculate(List.of(), baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(calculator.calculateScore(List.of(), baseTime)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("T-013, T-020: Many ratings from ONE actor do not raise confidence above a single rating")
    void repeatedRatingsFromOneActorDoNotRaiseConfidence() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();
        // Actor rates target 50 times over 5 hours
        for (int i = 0; i < 50; i++) {
            events.add(new ReputationEvent(
                    actor,
                    target,
                    1,
                    HonorKind.POSITIVE,
                    500.0,
                    null,
                    baseTime.minus(Duration.ofMinutes(i * 5))
            ));
        }

        // Distinct actor count must be 1
        assertThat(calculator.countDistinctActors(events)).isEqualTo(1);

        // Score must NOT exceed 1.0 (the max weight for one actor)
        double score = calculator.calculateScore(events, baseTime);
        assertThat(score).isLessThanOrEqualTo(1.0);
        assertThat(score).isGreaterThan(0.9);

        // Confidence cannot reach ESTABLISHED (threshold 5.0) or HIGH (threshold 15.0)
        ConfidenceLevel level = calculator.calculate(events, baseTime);
        assertThat(level).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("T-013, T-020: Many DISTINCT actors raise confidence to ESTABLISHED and HIGH")
    void distinctActorsRaiseConfidence() {
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // 6 distinct actors
        List<ReputationEvent> sixActors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            sixActors.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));
        }

        assertThat(calculator.countDistinctActors(sixActors)).isEqualTo(6);
        assertThat(calculator.calculate(sixActors, baseTime)).isEqualTo(ConfidenceLevel.ESTABLISHED);

        // 16 distinct actors
        List<ReputationEvent> sixteenActors = new ArrayList<>(sixActors);
        for (int i = 0; i < 10; i++) {
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            sixteenActors.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));
        }

        assertThat(calculator.countDistinctActors(sixteenActors)).isEqualTo(16);
        assertThat(calculator.calculate(sixteenActors, baseTime)).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("T-013: Rating age decay reduces confidence over time")
    void ratingAgeDecay() {
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // 16 distinct actors rated target at baseTime
        List<ReputationEvent> events = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            events.add(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));
        }

        // At baseTime: score is 16.0 -> HIGH
        assertThat(calculator.calculate(events, baseTime)).isEqualTo(ConfidenceLevel.HIGH);

        // After 1 half-life (30 days): score decays by 50% -> ~8.0 -> ESTABLISHED
        Instant after30Days = baseTime.plus(Duration.ofDays(30));
        assertThat(calculator.calculate(events, after30Days)).isEqualTo(ConfidenceLevel.ESTABLISHED);
        assertThat(calculator.calculateScore(events, after30Days)).isBetween(7.9, 8.1);

        // After 4 half-lives (120 days): score decays to 16 * (1/16) = 1.0 -> LOW
        Instant after120Days = baseTime.plus(Duration.ofDays(120));
        assertThat(calculator.calculate(events, after120Days)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(calculator.calculateScore(events, after120Days)).isBetween(0.99, 1.01);

        // After 200 days: score decays below 1.0 -> UNKNOWN
        Instant after200Days = baseTime.plus(Duration.ofDays(200));
        assertThat(calculator.calculate(events, after200Days)).isEqualTo(ConfidenceLevel.UNKNOWN);
    }

    @Test
    @DisplayName("T-013, T-091: Legacy imports contribute nothing to Confidence")
    void legacyImportsContributeNothing() {
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = List.of(
                new ReputationEvent(0L, null, target, 100, HonorKind.LEGACY_IMPORT, 0.0, "Old PlayerStatus", baseTime)
        );

        assertThat(calculator.countDistinctActors(events)).isZero();
        assertThat(calculator.calculateScore(events, baseTime)).isEqualTo(0.0);
        assertThat(calculator.calculate(events, baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);
    }

    @Test
    @DisplayName("Finding 11: Half-life boundary test with nanosecond precision")
    void halfLifeBoundaryNanosecondPrecision() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // Sub-second half-life: 500 milliseconds
        Duration halfLife = Duration.ofMillis(500);
        ConfidenceConfig subSecConfig = new ConfidenceConfig(0.5, 1.0, 2.0, halfLife);
        ConfidenceCalculator subSecCalc = new ConfidenceCalculator(subSecConfig);

        List<ReputationEvent> events = List.of(
                new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime)
        );

        // At exact half-life (baseTime + 500ms): score must be exactly 0.5 (weight = 2^(-1) = 0.5)
        Instant exactHalfLife = baseTime.plus(halfLife);
        double scoreAtHalfLife = subSecCalc.calculateScore(events, exactHalfLife);
        org.assertj.core.data.Offset<Double> tolerance = org.assertj.core.data.Offset.offset(1e-9);
        assertThat(scoreAtHalfLife).isCloseTo(0.5, tolerance);

        // At half of the half-life (250ms): score must be 2^(-0.5) ≈ 0.70710678
        Instant quarterSecond = baseTime.plus(Duration.ofMillis(250));
        double scoreAtQuarterSec = subSecCalc.calculateScore(events, quarterSecond);
        assertThat(scoreAtQuarterSec).isCloseTo(Math.pow(2.0, -0.5), tolerance);
    }
}
