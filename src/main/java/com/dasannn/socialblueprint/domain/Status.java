package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/**
 * Social status value type, wrapping a signed integer per SB-001.
 * Aggregate status is derived from immutable reputation events (T-012, SB-002).
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
        for (ReputationEvent event : events) {
            if (event != null) {
                total += event.delta();
            }
        }
        if (total > Integer.MAX_VALUE || total < Integer.MIN_VALUE) {
            throw new ArithmeticException("Derived status aggregate exceeds 32-bit signed integer range: " + total);
        }
        return Status.of((int) total);
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
