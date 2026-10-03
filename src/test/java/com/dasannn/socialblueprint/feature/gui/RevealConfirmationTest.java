package com.dasannn.socialblueprint.feature.gui;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RevealConfirmationTest {
    @Test
    void armConfirmExpirySwitchDisarmAndConsumeOnce() {
        Instant start = Instant.parse("2026-10-03T12:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        RevealConfirmation state = new RevealConfirmation(clock);
        UUID viewer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertThat(state.click(viewer, 10, 5)).isFalse();
        assertThat(state.armed(viewer)).isEqualTo(new RevealConfirmation.Arm(10, start.plusSeconds(5)));
        assertThat(state.armed(other)).isNull();
        now.set(start.plusSeconds(4));
        assertThat(state.click(viewer, 10, 5)).isTrue();
        assertThat(state.armed(viewer)).isNull();
        assertThat(state.click(viewer, 10, 5)).isFalse(); // Consumed confirmation cannot charge again.
        now.set(start.plusSeconds(9));
        assertThat(state.click(viewer, 10, 5)).isFalse(); // Exact deadline is too late; re-arm.
        assertThat(state.armed(viewer).expiry()).isEqualTo(start.plusSeconds(14));
        assertThat(state.click(viewer, 11, 5)).isFalse();
        assertThat(state.armed(viewer).eventId()).isEqualTo(11);
        assertThat(state.click(viewer, 10, 5)).isFalse(); // Switching back needs its own confirmation.
        state.disarm(viewer);
        assertThat(state.armed(viewer)).isNull();
        assertThat(state.click(viewer, 10, 5)).isFalse();
        now.set(start.plusSeconds(15));
        assertThat(state.armed(viewer)).isNull(); // Expiry without any second click.
        assertThat(state.click(viewer, 10, 5)).isFalse();
        assertThat(state.click(other, 20, 5)).isFalse();
        state.disarm(other);
        assertThat(state.click(viewer, 10, 5)).isTrue();
    }
}
