package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MentalStateLineTest {
    @Test
    void signedValueSelectsOneLineWithResolvedLevelsAndOneDecimalMagnitude() {
        var calculator = new PsychosisCalculator(PsychosisConfig.defaults());
        for (String prefix : new String[]{"chat.hover-mental-state", "status.profile-mental-state"}) {
            for (boolean detail : new boolean[]{false, true}) {
                var neutral = MentalStateLine.of(calculator.calculate(0), 0, prefix, detail);
                assertThat(neutral.key()).isEqualTo(prefix + "-neutral");
                assertThat(neutral.levelKey()).isNull();
                assertThat(neutral.placeholders()).isEmpty();
                var serene = MentalStateLine.of(calculator.calculate(43.75), 43.75, prefix, detail);
                assertThat(serene.key()).isEqualTo(prefix + "-serenity");
                assertThat(serene.levelKey()).isNull();
                assertThat(serene.placeholders()).containsExactlyEntriesOf(Map.of("value", "43.8"));
                for (var row : Map.of(-0.01, "low", -19.99, "low", -20.0, "medium",
                        -34.0, "medium", -50.0, "high", -80.0, "extreme", -100.0, "extreme").entrySet()) {
                    var line = MentalStateLine.of(calculator.calculate(row.getKey()), Math.abs(row.getKey()), prefix, detail);
                    assertThat(line.key()).isEqualTo(prefix + (detail ? "-psychosis-detail" : "-psychosis"));
                    assertThat(line.levelKey()).isEqualTo("psychosis." + row.getValue());
                    if (detail) assertThat(line.placeholders()).containsExactlyEntriesOf(
                            Map.of("value", String.format(java.util.Locale.ROOT, "%.1f", Math.abs(row.getKey()))));
                    else assertThat(line.placeholders()).isEmpty();
                }
            }
        }
        assertThat(MentalStateLine.of(PsychosisLevel.MEDIUM, 34, "status.profile-mental-state", true).placeholders())
                .containsEntry("value", "34.0");
    }
}
