package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class Release201Test {
    private static List<MindNotices.Notice> update(MindNotices notices, double value) {
        return notices.update(value, 5, true, true, true);
    }
    @Test void noticesAccumulateRiseAtExactBoundaryAndResetBaseline() {
        var notices = new MindNotices(-10);
        assertThat(update(notices, -14.9)).isEmpty();
        var rise = update(notices, -15);
        assertThat(rise).hasSize(1);
        assertThat(rise.getFirst().key()).isEqualTo("mind-notices.psychosis-rose");
        assertThat(rise.getFirst().values()).containsEntry("change", "5.0").containsEntry("now", "15.0");
        assertThat(update(notices, -19)).isEmpty();
        assertThat(update(notices, -20)).hasSize(1);
    }
    @Test void noticesFallAndCrossNeutralInBothDirections() {
        var notices = new MindNotices(-10);
        var fall = update(notices, -5);
        assertThat(fall.getFirst().key()).isEqualTo("mind-notices.psychosis-fell");
        var crossing = update(notices, 10);
        assertThat(crossing).extracting(MindNotices.Notice::key)
                .containsExactly("mind-notices.psychosis-fell", "mind-notices.serenity-rose");
        assertThat(crossing.getFirst().values()).containsEntry("change", "5.0").containsEntry("now", "0.0");
        assertThat(update(notices, -10)).extracting(MindNotices.Notice::key)
                .containsExactly("mind-notices.psychosis-rose", "mind-notices.serenity-fell");
    }
    @Test void joinAndDisabledDirectionsProduceNoCatchUp() {
        var notices = new MindNotices(40);
        assertThat(update(notices, 40)).isEmpty();
        assertThat(notices.update(50, 5, false, true, true)).isEmpty();
        assertThat(update(notices, 50)).isEmpty();
        assertThat(notices.update(60, 5, true, false, true)).isEmpty();
        assertThat(notices.update(50, 5, true, true, false)).isEmpty();
        assertThat(update(notices, 50)).isEmpty();
    }
    @Test void noticesRoundLikeProfileLine() {
        var notice = update(new MindNotices(0), 5.26).getFirst();
        assertThat(notice.values()).containsEntry("change", "5.3").containsEntry("now", "5.3");
    }
    @Test void reduceOnlyPsychosisWithExactNeutralAtOneHundredPercent() {
        assertThat(MindState.reducePsychosis(-50, 100)).isEqualTo(0.0);
        assertThat(Double.doubleToRawLongBits(MindState.reducePsychosis(-50, 100))).isZero();
        assertThat(MindState.reducePsychosis(-50, 40)).isEqualTo(-30);
        assertThat(MindState.reducePsychosis(50, 40)).isEqualTo(50);
        assertThat(MindState.reducePsychosis(0, 100)).isZero();
        assertThat(MindState.reducePsychosis(-50, 12.5)).isEqualTo(-43.75);
        for (double invalid : new double[] {0, -1, 101, Double.NaN, Double.POSITIVE_INFINITY})
            assertThatThrownBy(() -> MindState.reducePsychosis(-50, invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void prefixAndNameHaveTheChatSpacing() {
        assertThat(PlayerNameFormat.name("&7[&a||&7]", "Alex")).isEqualTo("&7[&a||&7] Alex");
        assertThat(PlayerNameFormat.name("", "Alex")).isEqualTo("Alex");
        assertThat(PlayerNameFormat.name(null, "Alex")).isEqualTo("Alex");
    }
}
