package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MindNumbersTest {
    @Test
    void roundsToOneDecimalAndDropsWholeNumberSuffixAndNegativeZero() {
        assertThat(MindNumbers.format(100.0)).isEqualTo("100");
        assertThat(MindNumbers.format(62.0)).isEqualTo("62");
        assertThat(MindNumbers.format(43.7)).isEqualTo("43.7");
        assertThat(MindNumbers.format(43.75)).isEqualTo("43.8");
        assertThat(MindNumbers.format(0.04)).isEqualTo("0");
        assertThat(MindNumbers.format(-0.0)).isEqualTo("0");
        assertThat(MindNumbers.format(-0.04)).isEqualTo("0");
        assertThat(MindNumbers.format(-62.0)).isEqualTo("-62");
        assertThat(MindNumbers.format(-43.7)).isEqualTo("-43.7");
    }
}
