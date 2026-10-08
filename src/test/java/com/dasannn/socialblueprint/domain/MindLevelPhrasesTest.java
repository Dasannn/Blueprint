package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MindLevelPhrasesTest {
    @Test void everyPsychosisTransitionUsesOnlyTheFinalLevelAndDirection() {
        var levels = List.of(PsychosisLevel.NEUTRAL, PsychosisLevel.LOW, PsychosisLevel.MEDIUM,
                PsychosisLevel.HIGH, PsychosisLevel.EXTREME);
        var names = List.of("neutral", "low", "medium", "high", "extreme");
        for (int before = 0; before < levels.size(); before++) {
            for (int after = 0; after < levels.size(); after++) {
                String expected = before == after ? null : "mind-levels.psychosis-"
                        + (after > before ? "rose." : "fell.") + names.get(after) + ".lines";
                assertThat(MindNotices.levelKey(levels.get(before), levels.get(after))).isEqualTo(expected);
            }
        }
    }

    @Test void loadedLevelAndSmallChangesUseImmediateLevelRatherThanFivePointBaseline() {
        var calculator = new PsychosisCalculator(PsychosisConfig.defaults());
        var baseline = new MindNotices(-19.9, calculator.calculate(-19.9));
        assertThat(baseline.updateLevel(calculator.calculate(-19.9), true, false)).isNull();
        assertThat(baseline.update(-20, 5, true, true, true)).isEmpty();
        assertThat(baseline.updateLevel(calculator.calculate(-20), true, false))
                .isEqualTo("mind-levels.psychosis-rose.medium.lines");
        assertThat(baseline.updateLevel(calculator.calculate(-19.9), true, false))
                .isEqualTo("mind-levels.psychosis-fell.low.lines");
        for (double threshold : List.of(20d, 50d, 80d)) {
            assertThat(MindNotices.levelKey(calculator.calculate(-threshold + .1), calculator.calculate(-threshold)))
                    .startsWith("mind-levels.psychosis-rose.");
        }
        var custom = new PsychosisCalculator(new PsychosisConfig(10, 30, 60));
        assertThat(MindNotices.levelKey(custom.calculate(-29.9), custom.calculate(-30)))
                .isEqualTo("mind-levels.psychosis-rose.high.lines");
    }

    @Test void disabledPhrasesAndWorldsTrackSilentlyWithoutLaterReplay() {
        var baseline = new MindNotices(-90, PsychosisLevel.EXTREME);
        assertThat(baseline.updateLevel(PsychosisLevel.NEUTRAL, true, true)).isNull();
        assertThat(baseline.updateLevel(PsychosisLevel.NEUTRAL, true, false)).isNull();
        assertThat(baseline.updateLevel(PsychosisLevel.HIGH, false, false)).isNull();
        assertThat(baseline.updateLevel(PsychosisLevel.HIGH, true, false)).isNull();
        assertThat(baseline.updateLevel(PsychosisLevel.LOW, true, false))
                .isEqualTo("mind-levels.psychosis-fell.low.lines");
    }

    @Test void serenityChangesDoNotHaveTheirOwnPhrases() {
        for (var level : PsychosisLevel.values()) {
            assertThat(MindNotices.levelKey(level, PsychosisLevel.SERENITY)).isNull();
        }
        assertThat(MindNotices.levelKey(PsychosisLevel.SERENITY, PsychosisLevel.NEUTRAL)).isNull();
        assertThat(MindNotices.levelKey(PsychosisLevel.SERENITY, PsychosisLevel.LOW))
                .isEqualTo("mind-levels.psychosis-rose.low.lines");
    }
}
