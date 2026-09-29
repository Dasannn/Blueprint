package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatGradientTest {

    @Test
    @DisplayName("T-041 / DoD 2, 3: Chat gradient produces strictly increasing brightness from #202020 to #FFFFFF")
    void chatGradientNineTiers() {
        Tier[] tiers = Tier.values();
        assertThat(tiers).hasSize(9);

        // Tier.CRIMINAL (-4) must be exactly near-black #202020 (never absolute black #000000)
        assertThat(ChatGradient.rgb(Tier.CRIMINAL)).isEqualTo(0x202020);
        assertThat(ChatGradient.hex(Tier.CRIMINAL)).isEqualTo("#202020");

        // Tier.ILUSTRE (+4) must be bright white #FFFFFF
        assertThat(ChatGradient.rgb(Tier.ILUSTRE)).isEqualTo(0xFFFFFF);
        assertThat(ChatGradient.hex(Tier.ILUSTRE)).isEqualTo("#FFFFFF");

        // Verify all 9 tiers have strictly monotonically increasing brightness
        int previousBrightness = -1;
        for (Tier tier : tiers) {
            int rgb = ChatGradient.rgb(tier);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // Grayscale check: R == G == B
            assertThat(r).isEqualTo(g).isEqualTo(b);

            // Never absolute black (SB-020)
            assertThat(r).isGreaterThanOrEqualTo(0x20);
            assertThat(r).isPositive();

            // Strictly increasing
            assertThat(r).isGreaterThan(previousBrightness);
            previousBrightness = r;
        }

        // Tier.PARTICULAR (neutral, 0)
        assertThat(ChatGradient.rgb(Tier.PARTICULAR)).isEqualTo(0x909090);
        assertThat(ChatGradient.hex(Tier.PARTICULAR)).isEqualTo("#909090");
    }

    @Test
    @DisplayName("T-041: Out-of-bounds levels clamp gracefully to ladder endpoints")
    void outOfBoundsLevelsClamped() {
        assertThat(ChatGradient.rgbByLevel(-10)).isEqualTo(ChatGradient.rgb(Tier.CRIMINAL));
        assertThat(ChatGradient.rgbByLevel(10)).isEqualTo(ChatGradient.rgb(Tier.ILUSTRE));
        assertThat(ChatGradient.rgbByLevel(0)).isEqualTo(ChatGradient.rgb(Tier.PARTICULAR));
    }

    @Test
    @DisplayName("T-041: TierLadder status resolution maps full status range to appropriate gradient")
    void ladderResolvesFullStatusRangeToGradient() {
        TierLadder ladder = TierLadder.of(Map.of(
                Tier.CRIMINAL, -30,
                Tier.FORAJIDO, -20,
                Tier.DELINCUENTE, -10,
                Tier.TEMERARIO, -1,
                Tier.PARTICULAR, 0,
                Tier.AFABLE, 5,
                Tier.HONORABLE, 15,
                Tier.INSIGNE, 30,
                Tier.ILUSTRE, 50
        ));

        // Extreme negative
        Tier tMin = ladder.resolve(Integer.MIN_VALUE);
        assertThat(tMin).isEqualTo(Tier.CRIMINAL);
        assertThat(ChatGradient.hex(tMin)).isEqualTo("#202020");

        // Negative boundary
        Tier tCrim = ladder.resolve(-30);
        assertThat(tCrim).isEqualTo(Tier.CRIMINAL);
        assertThat(ChatGradient.hex(tCrim)).isEqualTo("#202020");

        // Neutral 0
        Tier tZero = ladder.resolve(0);
        assertThat(tZero).isEqualTo(Tier.PARTICULAR);
        assertThat(ChatGradient.hex(tZero)).isEqualTo("#909090");

        // Positive boundary
        Tier tIlu = ladder.resolve(50);
        assertThat(tIlu).isEqualTo(Tier.ILUSTRE);
        assertThat(ChatGradient.hex(tIlu)).isEqualTo("#FFFFFF");

        // Extreme positive
        Tier tMax = ladder.resolve(Integer.MAX_VALUE);
        assertThat(tMax).isEqualTo(Tier.ILUSTRE);
        assertThat(ChatGradient.hex(tMax)).isEqualTo("#FFFFFF");
    }
}
