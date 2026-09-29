package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PsychosisCalculatorTest {

    private final PsychosisConfig config = new PsychosisConfig(Duration.ofHours(24), 2, 5, 10);
    private final PsychosisCalculator calculator = new PsychosisCalculator(config);
    private final Instant baseTime = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("T-014, SB-005: Player with no kills resolves to lowest level LOW")
    void noKillsResolvesToLow() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        assertThat(calculator.calculate(killer, List.of(), baseTime)).isEqualTo(PsychosisLevel.LOW);
    }

    @Test
    @DisplayName("T-014, SB-031: Duel kills are ignored and do not affect Killing Psychosis")
    void duelKillsAreIgnored() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());

        List<PsychosisEvent> duelKills = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            duelKills.add(new PsychosisEvent(killer, victim, CombatContext.DUEL, baseTime.minusSeconds(i * 60)));
        }

        // Even with 20 duel kills, qualifying kills is 0 and Psychosis remains LOW
        assertThat(calculator.countQualifyingKills(killer, duelKills, baseTime)).isZero();
        assertThat(calculator.calculate(killer, duelKills, baseTime)).isEqualTo(PsychosisLevel.LOW);
    }

    @Test
    @DisplayName("T-014, SB-032: Open-world kills raise Killing Psychosis level")
    void openWorldKillsRaisePsychosis() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());

        List<PsychosisEvent> events = new ArrayList<>();

        // 1 kill -> LOW (< 2)
        events.add(new PsychosisEvent(killer, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, baseTime));
        assertThat(calculator.calculate(killer, events, baseTime)).isEqualTo(PsychosisLevel.LOW);

        // 2 kills -> MEDIUM (>= 2 and < 5)
        events.add(new PsychosisEvent(killer, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, baseTime));
        assertThat(calculator.calculate(killer, events, baseTime)).isEqualTo(PsychosisLevel.MEDIUM);

        // 5 kills -> HIGH (>= 5 and < 10)
        for (int i = 0; i < 3; i++) {
            events.add(new PsychosisEvent(killer, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, baseTime));
        }
        assertThat(calculator.calculate(killer, events, baseTime)).isEqualTo(PsychosisLevel.HIGH);

        // 10 kills -> EXTREME (>= 10)
        for (int i = 0; i < 5; i++) {
            events.add(new PsychosisEvent(killer, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, baseTime));
        }
        assertThat(calculator.calculate(killer, events, baseTime)).isEqualTo(PsychosisLevel.EXTREME);
    }

    @Test
    @DisplayName("T-014, T-020: Rolling window expiry restores Killing Psychosis to LOW")
    void rollingWindowExpiryRestoresPsychosis() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());

        // 12 open-world kills at baseTime -> EXTREME
        List<PsychosisEvent> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            events.add(new PsychosisEvent(killer, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, baseTime));
        }

        assertThat(calculator.calculate(killer, events, baseTime)).isEqualTo(PsychosisLevel.EXTREME);

        // Fast forward 12 hours (within 24h window) -> still EXTREME
        Instant after12Hours = baseTime.plus(Duration.ofHours(12));
        assertThat(calculator.calculate(killer, events, after12Hours)).isEqualTo(PsychosisLevel.EXTREME);

        // Fast forward 25 hours (window has expired for all 12 kills)
        Instant after25Hours = baseTime.plus(Duration.ofHours(25));
        assertThat(calculator.countQualifyingKills(killer, events, after25Hours)).isZero();
        assertThat(calculator.calculate(killer, events, after25Hours)).isEqualTo(PsychosisLevel.LOW);
    }

    @Test
    @DisplayName("Finding 4: Psychosis rolling window uses half-open interval (now - window, now]")
    void windowEdgeHalfOpenBoundary() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());

        List<PsychosisEvent> events = List.of(
                new PsychosisEvent(killer, victim, CombatContext.OPEN, baseTime)
        );

        // Window is 24 hours. At exactly 24 hours after baseTime: now - window == baseTime -> excluded
        Instant exactOneWindow = baseTime.plus(Duration.ofHours(24));
        assertThat(calculator.countQualifyingKills(killer, events, exactOneWindow)).isZero();
        assertThat(calculator.calculate(killer, events, exactOneWindow)).isEqualTo(PsychosisLevel.LOW);

        // At 1 nanosecond before 24 hours: inside window -> included
        Instant justBefore = exactOneWindow.minusNanos(1);
        assertThat(calculator.countQualifyingKills(killer, events, justBefore)).isEqualTo(1);
    }
}
