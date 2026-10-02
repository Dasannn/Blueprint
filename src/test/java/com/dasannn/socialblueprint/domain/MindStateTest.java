package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MindStateTest {
    @Test void everyInputUsesItsRowOnBothSidesAndAtNeutral() {
        for (MindInput kind : MindInput.values()) {
            MindInputConfig config = kind.defaults();
            assertThat(MindState.apply(90, kind, config).after()).as(kind.id())
                    .isEqualTo(kind.bad() ? 90 - config.sereneAmount() : Math.min(100, 90 + config.sereneAmount()));
            assertThat(MindState.apply(-90, kind, config).after()).as(kind.id())
                    .isEqualTo(kind.bad() ? Math.max(-100, -90 - config.psychosisAmount()) : -90 + config.psychosisAmount());
            assertThat(MindState.apply(0, kind, config).after()).as(kind.id())
                    .isEqualTo(kind.bad() ? -config.psychosisAmount() : config.sereneAmount());
        }
    }
    @Test void noInputSpillsAcrossNeutralAndRequestedDeltaIsPreserved() {
        for (MindInput kind : MindInput.values()) {
            double before = kind.bad() ? 0.01 : -0.01;
            var result = MindState.apply(before, kind, kind.defaults());
            assertThat(result.after()).isZero();
            assertThat(result.appliedDelta()).isEqualTo(-before);
            assertThat(result.requestedDelta()).isEqualTo(kind.bad() ? -kind.defaults().sereneAmount() : kind.defaults().psychosisAmount());
        }
        assertThat(MindState.apply(10, MindInput.KILL, MindInput.KILL.defaults()).after()).isZero();
        assertThat(MindState.apply(0, MindInput.KILL, MindInput.KILL.defaults()).after()).isEqualTo(-10);
    }
    @Test void capsDisabledAndZeroAmounts() {
        for (MindInput kind : MindInput.values()) {
            double before = kind.bad() ? -99.99 : 99.99;
            var result = MindState.apply(before, kind, kind.defaults());
            assertThat(result.after()).isEqualTo(kind.bad() ? -100 : 100);
            assertThat(result.appliedDelta()).isEqualTo(result.after() - before);
            var disabled = MindState.apply(before, kind, new MindInputConfig(false, 10, 10, 0));
            assertThat(disabled.enabled()).isFalse();
            assertThat(disabled.after()).isEqualTo(before);
            assertThat(disabled.requestedDelta()).isZero();
            assertThat(MindState.apply(before, kind, new MindInputConfig(true, 0, 0, 0)).after()).isEqualTo(before);
        }
    }
    @Test void validationRejectsNonfiniteAndOutOfRangeValues() {
        for (double value : new double[]{-101,101,Double.NaN,Double.POSITIVE_INFINITY})
            assertThatThrownBy(() -> MindState.apply(value,MindInput.KILL,MindInput.KILL.defaults())).isInstanceOf(IllegalArgumentException.class);
        for (double value : new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new MindInputConfig(true,value,1,0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MindInputConfig(true,1,value,0)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MindInputConfig(true,1,1,-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
