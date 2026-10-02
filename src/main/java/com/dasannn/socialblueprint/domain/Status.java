package com.dasannn.socialblueprint.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Social status value type, wrapping a signed integer per SB-001.
 * Aggregate status is derived from immutable reputation events (T-012, SB-002, T-110, SB-006).
 */
public record Status(int value) implements Comparable<Status> {

    public static final Status ZERO = new Status(0);

    public static Status of(int value) {
        return value == 0 ? ZERO : new Status(value);
    }

    /**
     * Derives aggregate status from an iterable of reputation events (T-012, SB-002).
     * If the events list is empty, returns {@link #ZERO} (SB-005).
     */
    public static Status fromEvents(Iterable<ReputationEvent> events) {
        if (events == null) {
            return ZERO;
        }
        long total = 0;
        for (ReputationEvent event : activeEvents(events)) {
            if (event != null) {
                total += event.delta();
            }
        }
        if (total > Integer.MAX_VALUE || total < Integer.MIN_VALUE) {
            throw new ArithmeticException("Derived status aggregate exceeds 32-bit signed integer range: " + total);
        }
        return Status.of((int) total);
    }

    /**
     * Derives aggregate status from an iterable of reputation events with exponential decay
     * per T-110, SB-006, and Constitution §2.6.
     *
     * If decay is disabled or null, returns the undecayed sum identical to {@link #fromEvents(Iterable)}.
     * Administrative corrections (kind ADMIN_RESET and compensation events) never decay.
     */
    public static Status fromEvents(Iterable<ReputationEvent> events, DecayConfig decay, Instant now) {
        if (events == null) {
            return ZERO;
        }
        if (decay == null || !decay.enabled()) {
            return fromEvents(events);
        }
        Objects.requireNonNull(now, "Instant 'now' must not be null when decay is enabled");

        double weightedSum = 0.0;
        for (ReputationEvent event : activeEvents(events)) {
            if (event == null) {
                continue;
            }

            double weight;
            if (event.kind() == HonorKind.ADMIN_RESET) {
                // Administrative corrections and compensation events must not decay:
                // an administrative correction is a statement about the record, not an opinion with an age.
                weight = 1.0;
            } else {
                Duration age = Duration.between(event.createdAt(), now);
                if (age.isNegative()) {
                    // Clock skew: event timestamp is in the future relative to 'now'; treat age as 0 (full weight 1.0)
                    weight = 1.0;
                } else {
                    double ageSeconds = age.getSeconds() + (age.getNano() / 1_000_000_000.0);
                    double halfLifeSeconds = decay.halfLife().getSeconds() + (decay.halfLife().getNano() / 1_000_000_000.0);
                    double exponent = -ageSeconds / halfLifeSeconds;
                    weight = Math.pow(2.0, exponent);
                    if (weight > 1.0) {
                        weight = 1.0;
                    } else if (weight < 0.0) {
                        weight = 0.0;
                    }
                }
                if (weight < decay.floor()) {
                    weight = 0.0;
                }
            }

            if (weight > 0.0) {
                weightedSum += event.delta() * weight;
            }
        }

        if (weightedSum > Integer.MAX_VALUE || weightedSum < Integer.MIN_VALUE) {
            throw new ArithmeticException("Derived status aggregate exceeds 32-bit signed integer range: " + weightedSum);
        }

        // The weighted sum is rounded half away from zero using RoundingMode.HALF_UP on BigDecimal.
        // In Java, standard Math.round(-0.5) rounds towards positive infinity (yielding 0), which is asymmetric
        // and rounds negative values towards zero. Using BigDecimal with RoundingMode.HALF_UP
        // ensures exact symmetric rounding half away from zero (0.5 -> 1, -0.5 -> -1).
        // Once an event's absolute contribution drops below 0.5 (e.g. past one half-life for a delta of +/-1),
        // it rounds to 0.
        int rounded = BigDecimal.valueOf(weightedSum)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        return Status.of(rounded);
    }

    private static java.util.List<ReputationEvent> activeEvents(Iterable<ReputationEvent> events) {
        java.util.List<ReputationEvent> all = new java.util.ArrayList<>();
        java.util.Set<Long> revoked = new java.util.HashSet<>();
        for (ReputationEvent event : events) {
            if (event == null) continue;
            all.add(event);
            if (event.kind() == HonorKind.REVOCATION) revoked.add(event.revokedRatingId());
        }
        return all.stream().filter(e -> e.kind() != HonorKind.REVOCATION
                && e.revokedBy() == null && !revoked.contains(e.id())).toList();
    }

    public Status plus(int delta) {
        return Status.of(Math.addExact(this.value, delta));
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isNegative() {
        return value < 0;
    }

    public boolean isNeutral() {
        return value == 0;
    }

    @Override
    public int compareTo(Status other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
