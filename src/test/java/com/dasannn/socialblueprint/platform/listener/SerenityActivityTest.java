package com.dasannn.socialblueprint.platform.listener;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SerenityActivityTest {
    @Test void passiveMovementIsNotPlayerInput() {
        assertThat(SerenityActivityListener.hasMovementInput(false, false, false, false, false)).isFalse();
        assertThat(SerenityActivityListener.hasMovementInput(true, false, false, false, false)).isTrue();
        assertThat(SerenityActivityListener.hasMovementInput(false, true, false, false, false)).isTrue();
        assertThat(SerenityActivityListener.hasMovementInput(false, false, true, false, false)).isTrue();
        assertThat(SerenityActivityListener.hasMovementInput(false, false, false, true, false)).isTrue();
        assertThat(SerenityActivityListener.hasMovementInput(false, false, false, false, true)).isTrue();
    }
}
