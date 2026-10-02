package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.assertj.core.api.Assertions.*;

class SkyDecisionTest {
    @Test void escalatingRerollsHighOncePerEpisodeAndExtremeAlwaysCombines() {
        int[] draws = {0};
        Random random = new Random() {
            @Override public boolean nextBoolean() { return draws[0]++ % 2 == 0; }
        };
        assertThat(SkyDecision.choose(PsychosisLevel.HIGH, "escalating", random))
                .isEqualTo(new SkyDecision(true, false));
        assertThat(SkyDecision.choose(PsychosisLevel.HIGH, "escalating", random))
                .isEqualTo(new SkyDecision(false, true));
        assertThat(draws[0]).isEqualTo(2);
        assertThat(SkyDecision.choose(PsychosisLevel.EXTREME, "escalating", random))
                .isEqualTo(new SkyDecision(true, true));
        assertThat(draws[0]).isEqualTo(2);
    }

    @Test void explicitModesDoNotDrawRandomAndSkyStaysAboveItsFloor() {
        Random unused = new Random() {
            @Override public boolean nextBoolean() { throw new AssertionError("Unexpected sky reroll"); }
        };
        for (var level : List.of(PsychosisLevel.HIGH, PsychosisLevel.EXTREME)) {
            assertThat(SkyDecision.choose(level, "night", unused)).isEqualTo(new SkyDecision(true, false));
            assertThat(SkyDecision.choose(level, "storm", unused)).isEqualTo(new SkyDecision(false, true));
            assertThat(SkyDecision.choose(level, "both", unused)).isEqualTo(new SkyDecision(true, true));
        }
        for (var level : List.of(PsychosisLevel.SERENITY, PsychosisLevel.NEUTRAL, PsychosisLevel.LOW, PsychosisLevel.MEDIUM))
            for (String mode : List.of("night", "storm", "both", "escalating"))
                assertThat(SkyDecision.choose(level, mode, unused)).isEqualTo(new SkyDecision(false, false));
    }

    @Test void combinedSkyRestoresBothOwnedDimensionsIndependently() {
        var sky = new SkyPresentation(1200, true, "CLEAR", 18000, false, "DOWNFALL", true, true);
        assertThat(sky.ownsTime(18000, false)).isTrue();
        assertThat(sky.ownsWeather("DOWNFALL")).isTrue();
        assertThat(sky.previousTimeOffset()).isEqualTo(1200);
        assertThat(sky.previousTimeRelative()).isTrue();
        assertThat(sky.previousWeather()).isEqualTo("CLEAR");
        assertThat(sky.resetTime(true)).isFalse();
        assertThat(sky.resetWeather(true)).isFalse();
        assertThat(sky.resetTime(false)).isTrue();
        assertThat(sky.resetWeather(false)).isTrue();
        assertThat(sky.ownsTime(6000, false)).isFalse();
        assertThat(sky.ownsWeather("CLEAR")).isFalse();
        var tracking = new SkyPresentation(0, true, null, 18000, false, "DOWNFALL", true, true);
        assertThat(tracking.resetTime(true)).isTrue();
        assertThat(tracking.resetWeather(true)).isTrue();
    }
}
