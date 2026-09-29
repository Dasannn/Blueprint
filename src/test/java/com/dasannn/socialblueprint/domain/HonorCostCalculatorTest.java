package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorCostCalculatorTest {

    private final HonorCostConfig config = new HonorCostConfig(500.0, List.of(1.0, 1.5, 2.0, 3.0), Duration.ofHours(1));
    private final HonorCostCalculator calculator = new HonorCostCalculator(config);
    private final Instant baseTime = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("T-015, T-020: Cost at each multiplier step per Decision 0001")
    void costAtEachMultiplierStep() {
        // Step 0: 0 ratings in window -> multiplier 1.0 -> 500.0
        assertThat(calculator.multiplierForRatings(0)).isEqualTo(1.0);
        assertThat(calculator.calculateCost(0)).isEqualTo(500.0);

        // Step 1: 1 rating in window -> multiplier 1.5 -> 750.0
        assertThat(calculator.multiplierForRatings(1)).isEqualTo(1.5);
        assertThat(calculator.calculateCost(1)).isEqualTo(750.0);

        // Step 2: 2 ratings in window -> multiplier 2.0 -> 1000.0
        assertThat(calculator.multiplierForRatings(2)).isEqualTo(2.0);
        assertThat(calculator.calculateCost(2)).isEqualTo(1000.0);

        // Step 3: 3 ratings in window -> multiplier 3.0 -> 1500.0
        assertThat(calculator.multiplierForRatings(3)).isEqualTo(3.0);
        assertThat(calculator.calculateCost(3)).isEqualTo(1500.0);

        // Step 4+: capped at last multiplier (3.0) -> 1500.0
        assertThat(calculator.multiplierForRatings(4)).isEqualTo(3.0);
        assertThat(calculator.calculateCost(4)).isEqualTo(1500.0);
        assertThat(calculator.calculateCost(10)).isEqualTo(1500.0);
    }

    @Test
    @DisplayName("T-015: Negative ratings count throws IllegalArgumentException")
    void negativeRatingsThrows() {
        assertThatThrownBy(() -> calculator.calculateCost(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T-015, T-020: Rolling window expires and resets progressive cost multiplier")
    void progressiveCostWindowExpiry() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target1 = PlayerId.of(UUID.randomUUID());
        PlayerId target2 = PlayerId.of(UUID.randomUUID());
        PlayerId target3 = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = new ArrayList<>();
        // Actor issues 3 ratings at baseTime
        events.add(new ReputationEvent(actor, target1, 1, HonorKind.POSITIVE, 500.0, null, baseTime));
        events.add(new ReputationEvent(actor, target2, 1, HonorKind.POSITIVE, 750.0, null, baseTime.plusSeconds(60)));
        events.add(new ReputationEvent(actor, target3, 1, HonorKind.POSITIVE, 1000.0, null, baseTime.plusSeconds(120)));

        // At baseTime + 10 minutes (3 ratings in window): next cost is at step 3 -> 1500.0
        Instant at10Min = baseTime.plus(Duration.ofMinutes(10));
        assertThat(calculator.countActorRatingsInWindow(actor, events, at10Min)).isEqualTo(3);
        assertThat(calculator.calculateCost(actor, events, at10Min)).isEqualTo(1500.0);

        // After 65 minutes (window is 1 hour): all 3 previous ratings expire from the window
        Instant at65Min = baseTime.plus(Duration.ofMinutes(65));
        assertThat(calculator.countActorRatingsInWindow(actor, events, at65Min)).isZero();
        assertThat(calculator.calculateCost(actor, events, at65Min)).isEqualTo(500.0);
    }

    @Test
    @DisplayName("Finding 4: Cost calculator rolling window uses half-open interval (now - window, now]")
    void windowEdgeHalfOpenBoundary() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> events = List.of(
                new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime)
        );

        // At exactly one window (baseTime + 1 hour): now - window == baseTime -> excluded
        Instant exactOneWindow = baseTime.plus(Duration.ofHours(1));
        assertThat(calculator.countActorRatingsInWindow(actor, events, exactOneWindow)).isZero();
        assertThat(calculator.calculateCost(actor, events, exactOneWindow)).isEqualTo(500.0);

        // At 1 nanosecond before 1 hour has elapsed: inside window
        Instant justBefore = exactOneWindow.minusNanos(1);
        assertThat(calculator.countActorRatingsInWindow(actor, events, justBefore)).isEqualTo(1);
        assertThat(calculator.calculateCost(actor, events, justBefore)).isEqualTo(750.0);
    }

    @Test
    @DisplayName("Fix 2: calculateCost throws ArithmeticException if finite base and multiplier multiply to infinity")
    void infiniteCostThrowsArithmeticException() {
        HonorCostConfig infiniteConfig = new HonorCostConfig(Double.MAX_VALUE, List.of(2.0), Duration.ofHours(1));
        HonorCostCalculator infiniteCalc = new HonorCostCalculator(infiniteConfig);

        assertThatThrownBy(() -> infiniteCalc.calculateCost(0))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("overflowed to non-finite value");
    }
}
