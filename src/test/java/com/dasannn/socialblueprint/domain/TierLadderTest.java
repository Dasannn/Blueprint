package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierLadderTest {

    private TierLadder standardLadder;

    @BeforeEach
    void setUp() {
        Map<Tier, Integer> thresholds = new EnumMap<>(Tier.class);
        thresholds.put(Tier.CRIMINAL, -30);
        thresholds.put(Tier.FORAJIDO, -20);
        thresholds.put(Tier.DELINCUENTE, -10);
        thresholds.put(Tier.TEMERARIO, -1);
        thresholds.put(Tier.PARTICULAR, 0);
        thresholds.put(Tier.AFABLE, 5);
        thresholds.put(Tier.HONORABLE, 15);
        thresholds.put(Tier.INSIGNE, 30);
        thresholds.put(Tier.ILUSTRE, 50);

        standardLadder = new TierLadder(thresholds);
    }

    @ParameterizedTest(name = "Status {0} resolves to {1}")
    @CsvSource({
            "-2147483648, CRIMINAL",
            "-100, CRIMINAL",
            "-31, CRIMINAL",
            "-30, CRIMINAL",
            "-29, FORAJIDO",
            "-20, FORAJIDO",
            "-19, DELINCUENTE",
            "-10, DELINCUENTE",
            "-9, TEMERARIO",
            "-1, TEMERARIO",
            "0, PARTICULAR",
            "1, PARTICULAR",
            "4, PARTICULAR",
            "5, AFABLE",
            "14, AFABLE",
            "15, HONORABLE",
            "29, HONORABLE",
            "30, INSIGNE",
            "49, INSIGNE",
            "50, ILUSTRE",
            "100, ILUSTRE",
            "2147483647, ILUSTRE"
    })
    @DisplayName("T-011, T-020: Resolves tiers correctly across full signed range including 0 and both extremes")
    void resolveAcrossFullRange(int status, Tier expectedTier) {
        assertThat(standardLadder.resolve(status)).isEqualTo(expectedTier);
        assertThat(standardLadder.resolve(Status.of(status))).isEqualTo(expectedTier);
    }

    @Test
    @DisplayName("T-011: Exactly 0 resolves to neutral tier Particular")
    void zeroResolvesToParticular() {
        assertThat(standardLadder.resolve(0)).isEqualTo(Tier.PARTICULAR);
        assertThat(standardLadder.resolve(Status.ZERO)).isEqualTo(Tier.PARTICULAR);
    }

    @Test
    @DisplayName("T-011: Shipped config.yml has positive thresholds on negative tiers and must be rejected naming the offending tier")
    void shippedMalformedConfigMustBeRejected() {
        // As shipped in config.yml: Criminal=30, Forajido=20, Delincuente=10, Temerario=1, Particular=0, Afable=5...
        Map<Tier, Integer> shippedConfigThresholds = new EnumMap<>(Tier.class);
        shippedConfigThresholds.put(Tier.CRIMINAL, 30);
        shippedConfigThresholds.put(Tier.FORAJIDO, 20);
        shippedConfigThresholds.put(Tier.DELINCUENTE, 10);
        shippedConfigThresholds.put(Tier.TEMERARIO, 1);
        shippedConfigThresholds.put(Tier.PARTICULAR, 0);
        shippedConfigThresholds.put(Tier.AFABLE, 5);
        shippedConfigThresholds.put(Tier.HONORABLE, 15);
        shippedConfigThresholds.put(Tier.INSIGNE, 30);
        shippedConfigThresholds.put(Tier.ILUSTRE, 50);

        assertThatThrownBy(() -> new TierLadder(shippedConfigThresholds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offending tier")
                .matches(e -> e.getMessage().contains("Criminal")
                        || e.getMessage().contains("Temerario")
                        || e.getMessage().contains("Forajido")
                        || e.getMessage().contains("Delincuente"));
    }

    @Test
    @DisplayName("T-011: Non-zero Particular threshold is rejected naming Particular")
    void particularMustBeZero() {
        Map<Tier, Integer> map = validMap();
        map.put(Tier.PARTICULAR, 1);

        assertThatThrownBy(() -> new TierLadder(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offending tier")
                .hasMessageContaining("Particular");
    }

    @Test
    @DisplayName("T-011: Positive threshold on negative tier is rejected naming that tier")
    void negativeTierMustBeNegative() {
        Map<Tier, Integer> map = validMap();
        map.put(Tier.TEMERARIO, 0);

        assertThatThrownBy(() -> new TierLadder(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offending tier")
                .hasMessageContaining("Temerario");
    }

    @Test
    @DisplayName("T-011: Out of order negative tiers rejected naming offending tier")
    void outOfOrderNegativeTiers() {
        Map<Tier, Integer> map = validMap();
        map.put(Tier.DELINCUENTE, -1); // Delincuente equal to Temerario (-1)
        map.put(Tier.TEMERARIO, -1);

        assertThatThrownBy(() -> new TierLadder(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offending tier")
                .hasMessageContaining("Delincuente");
    }

    @Test
    @DisplayName("T-011: Out of order positive tiers rejected naming offending tier")
    void outOfOrderPositiveTiers() {
        Map<Tier, Integer> map = validMap();
        map.put(Tier.HONORABLE, 5); // equal to Afable (5)

        assertThatThrownBy(() -> new TierLadder(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offending tier")
                .hasMessageContaining("Honorable");
    }

    @Test
    @DisplayName("T-011: Missing tier in ladder rejected naming the missing tier")
    void missingTierRejected() {
        Map<Tier, Integer> map = validMap();
        map.remove(Tier.ILUSTRE);

        assertThatThrownBy(() -> new TierLadder(map))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing threshold")
                .hasMessageContaining("Ilustre");
    }

    private static Map<Tier, Integer> validMap() {
        Map<Tier, Integer> map = new EnumMap<>(Tier.class);
        map.put(Tier.CRIMINAL, -30);
        map.put(Tier.FORAJIDO, -20);
        map.put(Tier.DELINCUENTE, -10);
        map.put(Tier.TEMERARIO, -1);
        map.put(Tier.PARTICULAR, 0);
        map.put(Tier.AFABLE, 5);
        map.put(Tier.HONORABLE, 15);
        map.put(Tier.INSIGNE, 30);
        map.put(Tier.ILUSTRE, 50);
        return map;
    }
}
