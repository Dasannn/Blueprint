package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueTypesTest {

    @Test
    @DisplayName("T-010: PlayerId wraps UUID and rejects null")
    void playerIdWrappingAndValidation() {
        UUID uuid = UUID.randomUUID();
        PlayerId id = PlayerId.of(uuid);

        assertThat(id.uuid()).isEqualTo(uuid);
        assertThat(id.value()).isEqualTo(uuid);
        assertThat(id.toString()).isEqualTo(uuid.toString());
        assertThat(PlayerId.fromString(uuid.toString())).isEqualTo(id);

        assertThatThrownBy(() -> new PlayerId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("T-010: Status wraps signed integer with zero and sign checks")
    void statusProperties() {
        assertThat(Status.ZERO.value()).isZero();
        assertThat(Status.ZERO.isNeutral()).isTrue();
        assertThat(Status.of(5).isPositive()).isTrue();
        assertThat(Status.of(-5).isNegative()).isTrue();
        assertThat(Status.of(0)).isSameAs(Status.ZERO);
        assertThat(Status.of(10).plus(-4)).isEqualTo(Status.of(6));
    }

    @Test
    @DisplayName("T-010: Tier enum contains exactly nine tiers with preserved identity")
    void tierPreservedIdentity() {
        assertThat(Tier.values()).hasSize(9);
        assertThat(Tier.CRIMINAL.displayName()).isEqualTo("Criminal");
        assertThat(Tier.CRIMINAL.configKey()).isEqualTo("tier-4");
        assertThat(Tier.CRIMINAL.isNegative()).isTrue();

        assertThat(Tier.PARTICULAR.displayName()).isEqualTo("Particular");
        assertThat(Tier.PARTICULAR.configKey()).isEqualTo("tier0");
        assertThat(Tier.PARTICULAR.isNeutral()).isTrue();

        assertThat(Tier.ILUSTRE.displayName()).isEqualTo("Ilustre");
        assertThat(Tier.ILUSTRE.configKey()).isEqualTo("tier4");
        assertThat(Tier.ILUSTRE.isPositive()).isTrue();

        assertThat(Tier.fromConfigKey("tier-4")).isEqualTo(Tier.CRIMINAL);
        assertThat(Tier.fromDisplayName("Particular")).isEqualTo(Tier.PARTICULAR);
    }

    @Test
    @DisplayName("T-010: ConfidenceLevel contains 4 levels")
    void confidenceLevels() {
        assertThat(ConfidenceLevel.values()).containsExactly(
                ConfidenceLevel.UNKNOWN,
                ConfidenceLevel.LOW,
                ConfidenceLevel.ESTABLISHED,
                ConfidenceLevel.HIGH
        );
    }

    @Test
    @DisplayName("T-010: PsychosisLevel contains 4 levels with LOW as lowest")
    void psychosisLevels() {
        assertThat(PsychosisLevel.values()).containsExactly(
                PsychosisLevel.LOW,
                PsychosisLevel.MEDIUM,
                PsychosisLevel.HIGH,
                PsychosisLevel.EXTREME
        );
    }

    @Test
    @DisplayName("T-010: HonorKind covers player actions, admin actions and legacy import")
    void honorKinds() {
        assertThat(HonorKind.POSITIVE.isPositive()).isTrue();
        assertThat(HonorKind.POSITIVE.isPlayerHonor()).isTrue();
        assertThat(HonorKind.POSITIVE.contributesToConfidence()).isTrue();

        assertThat(HonorKind.NEGATIVE.isNegative()).isTrue();
        assertThat(HonorKind.NEGATIVE.isPlayerHonor()).isTrue();
        assertThat(HonorKind.NEGATIVE.contributesToConfidence()).isTrue();

        assertThat(HonorKind.GIVE).isEqualTo(HonorKind.POSITIVE);
        assertThat(HonorKind.REMOVE).isEqualTo(HonorKind.NEGATIVE);

        assertThat(HonorKind.LEGACY_IMPORT.contributesToConfidence()).isFalse();
        assertThat(HonorKind.ADMIN_RESET.contributesToConfidence()).isFalse();
    }

    @Test
    @DisplayName("T-010: ReputationEvent enforces domain invariants")
    void reputationEventInvariants() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        // Actor cannot rate self
        assertThatThrownBy(() -> new ReputationEvent(actor, actor, 1, HonorKind.POSITIVE, 500.0, "reason", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot rate themselves");

        // Cost cannot be negative
        assertThatThrownBy(() -> new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, -10.0, "reason", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cost cannot be negative");

        // Positive honor must have delta > 0
        assertThatThrownBy(() -> new ReputationEvent(actor, target, -1, HonorKind.POSITIVE, 500.0, null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive delta");

        // Negative honor must have delta < 0
        assertThatThrownBy(() -> new ReputationEvent(actor, target, 1, HonorKind.NEGATIVE, 500.0, "bad", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative delta");

        // Negative honor requires a written reason (SB-056)
        assertThatThrownBy(() -> new ReputationEvent(actor, target, -1, HonorKind.NEGATIVE, 500.0, null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a written reason");

        assertThatThrownBy(() -> new ReputationEvent(actor, target, -1, HonorKind.NEGATIVE, 500.0, "   ", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a written reason");

        // Positive honor without reason is allowed (SB-056)
        ReputationEvent validPositive = new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, now);
        assertThat(validPositive.reason()).isNull();
        assertThat(validPositive.delta()).isEqualTo(1);

        // Finding 6: Non-finite cost is rejected, zero cost is allowed (SB-058 admin actions)
        assertThatThrownBy(() -> new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, Double.NaN, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, Double.POSITIVE_INFINITY, null, now))
                .isInstanceOf(IllegalArgumentException.class);

        ReputationEvent freeAdminEvent = new ReputationEvent(0L, actor, target, 5, HonorKind.ADMIN_GIVE, 0.0, "bonus", now);
        assertThat(freeAdminEvent.cost()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Finding 7: PlayerId represents CONSOLE as explicit non-player actor")
    void playerIdConsoleActor() {
        PlayerId console = PlayerId.CONSOLE;
        assertThat(console.isConsole()).isTrue();
        assertThat(console.toString()).isEqualTo("CONSOLE");
        assertThat(PlayerId.fromString("CONSOLE")).isEqualTo(console);
        assertThat(PlayerId.fromString("console")).isEqualTo(console);
        assertThat(PlayerId.of(new UUID(0L, 0L))).isEqualTo(console);

        PlayerId player = PlayerId.of(UUID.randomUUID());
        assertThat(player.isConsole()).isFalse();
        assertThat(player.toString()).isEqualTo(player.uuid().toString());
    }

    @Test
    @DisplayName("Finding 6: HonorCostConfig rejects free, negative, and non-finite charges per Constitution §2.4")
    void honorCostConfigValidation() {
        java.time.Duration window = java.time.Duration.ofHours(1);

        // Base cost cannot be 0.0, negative, NaN or Infinity
        assertThatThrownBy(() -> new HonorCostConfig(0.0, java.util.List.of(1.0), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(-100.0, java.util.List.of(1.0), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(Double.NaN, java.util.List.of(1.0), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(Double.POSITIVE_INFINITY, java.util.List.of(1.0), window))
                .isInstanceOf(IllegalArgumentException.class);

        // Multipliers cannot contain 0.0, negative, NaN or Infinity
        assertThatThrownBy(() -> new HonorCostConfig(500.0, java.util.List.of(0.0), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(500.0, java.util.List.of(-1.0), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(500.0, java.util.List.of(Double.NaN), window))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostConfig(500.0, java.util.List.of(Double.POSITIVE_INFINITY), window))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Finding 11: ConfidenceConfig rejects non-finite thresholds and non-positive half-life")
    void confidenceConfigValidation() {
        java.time.Duration halfLife = java.time.Duration.ofDays(30);

        // NaN / Infinity lowThreshold
        assertThatThrownBy(() -> new ConfidenceConfig(Double.NaN, 5.0, 15.0, halfLife))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfidenceConfig(Double.POSITIVE_INFINITY, 5.0, 15.0, halfLife))
                .isInstanceOf(IllegalArgumentException.class);

        // NaN / Infinity establishedThreshold
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, Double.NaN, 15.0, halfLife))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, Double.POSITIVE_INFINITY, 15.0, halfLife))
                .isInstanceOf(IllegalArgumentException.class);

        // NaN / Infinity highThreshold
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, 5.0, Double.NaN, halfLife))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, 5.0, Double.POSITIVE_INFINITY, halfLife))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-positive half-life
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, 5.0, 15.0, java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfidenceConfig(1.0, 5.0, 15.0, java.time.Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
