package com.dasannn.socialblueprint.feature.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VersionComparatorTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, 1.0, UP_TO_DATE",
            "1.0, v1.0, UP_TO_DATE",
            "v1.0.0, 1.0, UP_TO_DATE",
            "1.0, 1.0.0, UP_TO_DATE",
            "1.0, 1.1, OUTDATED",
            "1.0, v1.1, OUTDATED",
            "1.0.0, 1.0.1, OUTDATED",
            "1.0.9, 1.1.0, OUTDATED",
            "1.0, 2.0, OUTDATED",
            "1.0-SNAPSHOT, 1.0, OUTDATED",
            "1.0-beta, 1.0, OUTDATED",
            "1.1, 1.0, AHEAD",
            "v1.1, 1.0, AHEAD",
            "1.0.1, 1.0.0, AHEAD",
            "2.0-SNAPSHOT, 1.0, AHEAD",
            "1.0, 1.0-beta, AHEAD"
    })
    @DisplayName("T-081: VersionComparator correctly identifies UP_TO_DATE, OUTDATED, and AHEAD")
    void testVersionComparison(String running, String latest, VersionComparison expected) {
        VersionComparison actual = VersionComparator.compare(running, latest);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("T-081 / DoD 5: Null or blank versions evaluate to UNKNOWN")
    void nullOrBlankReturnsUnknown() {
        assertThat(VersionComparator.compare(null, "1.0")).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(VersionComparator.compare("1.0", null)).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(VersionComparator.compare("", "1.0")).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(VersionComparator.compare("1.0", "  ")).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(VersionComparator.compare(null, null)).isEqualTo(VersionComparison.UNKNOWN);
    }

    @Test
    @DisplayName("T-081: Test fails if comparison logic is inverted")
    void reversibilityCheck() {
        // A test that passes either way proves nothing (_shared-rules.md)
        assertThat(VersionComparator.compare("1.0", "2.0")).isEqualTo(VersionComparison.OUTDATED);
        assertThat(VersionComparator.compare("2.0", "1.0")).isEqualTo(VersionComparison.AHEAD);
        assertThat(VersionComparator.compare("1.0", "2.0")).isNotEqualTo(VersionComparison.AHEAD);
    }
}
