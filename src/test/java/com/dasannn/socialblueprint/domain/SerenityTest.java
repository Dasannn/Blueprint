package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SerenityTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    @Test void oneMetricHasExclusiveDirections() {
        var calculator = new PsychosisCalculator(PsychosisConfig.defaults());
        assertThat(calculator.calculate(0)).isEqualTo(PsychosisLevel.NEUTRAL);
        assertThat(calculator.calculate(1)).isEqualTo(PsychosisLevel.SERENITY);
        assertThat(calculator.calculate(-10)).isEqualTo(PsychosisLevel.LOW);
        for (var direction : List.of(PsychosisLevel.NEUTRAL, PsychosisLevel.SERENITY)) {
            assertThat(direction.hasMadnessEffects()).isFalse();
            for (int n = 0; n < 100; n++) assertThat(ChatCorruption.corrupt("a peaceful message remains intact for everybody", direction,
                    1, n, ChatCorruptionConfig.DEFAULT)).isEqualTo("a peaceful message remains intact for everybody");
        }
    }

    @Test void linearGoodInputsReachTheCeilingWithoutBankingSurplus() {
        double value = 0;
        for (int i = 0; i < 100; i++) value = MindState.apply(value, MindInput.CLEAN_DAY, MindInput.CLEAN_DAY.defaults()).after();
        assertThat(value).isEqualTo(100);
        assertThat(MindState.apply(value, MindInput.CLEAN_DAY, MindInput.CLEAN_DAY.defaults()).after()).isEqualTo(100);
        assertThat(MindState.apply(value, MindInput.KILL, MindInput.KILL.defaults()).after()).isEqualTo(75);
    }

    @Test void onlyActiveOnlineIntervalsCountForCleanDayTracking() {
        var config = new SerenityConfig(100, 100, 2.5);
        var session = new SerenitySession(0, null, 0, 0);
        session.advance(1000, NOW, WINDOW, config);
        assertThat(session.creditedMillis()).isZero();
        session.activity(1000);
        for (int i = 2; i <= 6; i++) session.advance(i * 1000, NOW.plusSeconds(i), WINDOW, config);
        assertThat(session.creditedMillis()).isEqualTo(2500);
        session.activity(6000);
        session.afk(true);
        session.activity(6000);
        session.advance(7000, NOW.plusSeconds(7), WINDOW, config);
        session.afk(false);
        session.advance(8000, NOW.plusSeconds(8), WINDOW, config);
        assertThat(session.creditedMillis()).isEqualTo(2500);
        session.activity(8000);
        session.advance(9000, NOW.plusSeconds(9), WINDOW, config);
        assertThat(session.creditedMillis()).isEqualTo(3500);
        // Restart has no activity marker and no persisted session timestamps.
        var restarted = new SerenitySession(session.creditedMillis(), null, 0, 90_000);
        restarted.advance(91_000, NOW.plus(Duration.ofDays(10)), WINDOW, config);
        assertThat(restarted.creditedMillis()).isEqualTo(3500);
        session.kill(NOW, 1);
        session.activity(9000);
        session.advance(10_000, NOW.plusSeconds(1), WINDOW, config);
        assertThat(session.creditedMillis()).isEqualTo(1000);
        // A bad action restarts active accounting, with no rolling-window recovery barrier.

    }

    @Test void serverStallEarnsNoBacklog() {
        var session = new SerenitySession(0, null, 0, 0);
        session.activity(0);
        session.advance(86_400_000, NOW.plusSeconds(86400), WINDOW, SerenityConfig.DEFAULT);
        assertThat(session.creditedMillis()).isZero();
    }
}
