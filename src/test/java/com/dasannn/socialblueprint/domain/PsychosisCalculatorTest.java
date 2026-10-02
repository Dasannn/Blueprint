package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PsychosisCalculatorTest {
    private final PsychosisCalculator calculator = new PsychosisCalculator(PsychosisConfig.defaults());
    @Test void signedValueResolvesEveryBoundary() {
        assertThat(calculator.calculate(0)).isEqualTo(PsychosisLevel.NEUTRAL);
        assertThat(calculator.calculate(0.02)).isEqualTo(PsychosisLevel.SERENITY);
        assertThat(calculator.calculate(100)).isEqualTo(PsychosisLevel.SERENITY);
        assertThat(calculator.calculate(-0.02)).isEqualTo(PsychosisLevel.LOW);
        assertThat(calculator.calculate(-19.99)).isEqualTo(PsychosisLevel.LOW);
        assertThat(calculator.calculate(-20)).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(calculator.calculate(-49.99)).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(calculator.calculate(-50)).isEqualTo(PsychosisLevel.HIGH);
        assertThat(calculator.calculate(-79.99)).isEqualTo(PsychosisLevel.HIGH);
        assertThat(calculator.calculate(-80)).isEqualTo(PsychosisLevel.EXTREME);
        assertThat(calculator.calculate(-100)).isEqualTo(PsychosisLevel.EXTREME);
    }
    @Test void thresholdsAreConfigurableMagnitudes() {
        var custom = new PsychosisCalculator(new PsychosisConfig(1, 2, 3));
        assertThat(custom.calculate(-1)).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(custom.calculate(-2)).isEqualTo(PsychosisLevel.HIGH);
        assertThat(custom.calculate(-3)).isEqualTo(PsychosisLevel.EXTREME);
    }
    @Test void thresholdsMustIncreaseInsideRange() {
        for (double[] bad : new double[][]{{0,50,80},{20,20,80},{20,50,50},{20,50,101},{Double.NaN,50,80},{20,Double.POSITIVE_INFINITY,80}})
            assertThatThrownBy(() -> new PsychosisConfig(bad[0],bad[1],bad[2])).isInstanceOf(IllegalArgumentException.class);
        for (double bad : new double[]{-101,101,Double.NaN,Double.POSITIVE_INFINITY})
            assertThatThrownBy(() -> calculator.calculate(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sequentialKillsAreLinearAndNeverExpire() {
        double value = 0;
        for (int i = 1; i <= 12; i++) {
            value = MindState.apply(value, MindInput.KILL, MindInput.KILL.defaults()).after();
            assertThat(value).isEqualTo(-Math.min(100, 10 * i));
        }
        // The calculator accepts only the persisted value: elapsed time cannot change it.
        assertThat(calculator.calculate(value)).isEqualTo(PsychosisLevel.EXTREME);
    }
}
