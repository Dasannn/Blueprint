package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.dasannn.socialblueprint.domain.PsychosisConfig;
import com.dasannn.socialblueprint.domain.PsychosisLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectsConfigValidationTest {

    private YamlConfiguration loadValidYaml() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertThat(stream).isNotNull();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void windowDefaultsTo72HoursAndAcceptsConfiguredWindow() {
        YamlConfiguration yaml = loadValidYaml();
        assertThat(PluginConfig.load(yaml).psychosis().window()).isEqualTo(Duration.ofHours(72));
        assertThat(PsychosisConfig.defaults().window()).isEqualTo(Duration.ofHours(72));
        yaml.set("psychosis.window", "48h");
        assertThat(PluginConfig.load(yaml).psychosis().window()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    void zeroConfigurationCannotRemoveQuietOrGradation() {
        YamlConfiguration yaml = loadValidYaml();
        for (String level : java.util.List.of("medium", "high", "extreme")) {
            yaml.set("effects.quiet-interval." + level, "0s");
        }
        for (String effect : java.util.List.of("silverfish", "whisper", "creeper", "fake-announcement")) {
            yaml.set("effects." + effect + ".cooldown", "0s");
        }
        EffectsConfigSection cfg = PluginConfig.load(yaml).effects();
        // The requirement is that configuration cannot remove the quiet, and
        // that calmer levels stay quieter. Pinning an exact number here would
        // fail whenever a floor rises for a good reason, which says nothing
        // about the guarantee.
        assertThat(cfg.quietInterval(PsychosisLevel.MEDIUM))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(90));
        assertThat(cfg.quietInterval(PsychosisLevel.HIGH))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(cfg.quietInterval(PsychosisLevel.EXTREME))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(cfg.quietInterval(PsychosisLevel.MEDIUM))
                .isGreaterThan(cfg.quietInterval(PsychosisLevel.HIGH));
        assertThat(cfg.quietInterval(PsychosisLevel.HIGH))
                .isGreaterThan(cfg.quietInterval(PsychosisLevel.EXTREME));
    }

    @Test
    void invalidEpisodeBoundAndNonDecreasingCadenceAreRejected() {
        for (int bound : java.util.List.of(0, -1, 201)) {
            YamlConfiguration yaml = loadValidYaml();
            yaml.set("effects.max-episode-ticks", bound);
            assertThatThrownBy(() -> PluginConfig.load(yaml)).isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("effects.max-episode-ticks");
        }
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.quiet-interval.high", "10m");
        assertThatThrownBy(() -> PluginConfig.load(yaml)).isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.quiet-interval");
    }

    @Test
    @DisplayName("T-070 / SB-043: Missing effects section loads clean defaults")
    void missingEffectsSectionUsesDefaults() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects", null);

        PluginConfig config = PluginConfig.load(yaml);
        assertThat(config.effects()).isNotNull();
        assertThat(config.effects().checkInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.effects().silverfish().sessionCap()).isEqualTo(3);
        assertThat(config.effects().whisper().sessionCap()).isEqualTo(5);
        assertThat(config.effects().creeper().sessionCap()).isEqualTo(3);
        assertThat(config.effects().fakeAnnouncement().sessionCap()).isEqualTo(2);
    }

    @Test
    @DisplayName("T-070: Negative check-interval fails validation naming offending key")
    void negativeCheckIntervalFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.check-interval", "-10s");

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.check-interval")
                .matches(e -> "effects.check-interval".equals(((ConfigValidationException) e).key()));
    }

    @Test
    @DisplayName("T-070: Negative cooldown fails validation naming offending key")
    void negativeCooldownFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.silverfish.cooldown", "-5m");

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.silverfish.cooldown")
                .matches(e -> "effects.silverfish.cooldown".equals(((ConfigValidationException) e).key()));
    }

    @Test
    @DisplayName("T-070: Negative session-cap fails validation naming offending key")
    void negativeSessionCapFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.whisper.session-cap", -1);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.whisper.session-cap")
                .matches(e -> "effects.whisper.session-cap".equals(((ConfigValidationException) e).key()));
    }

    @Test
    @DisplayName("T-070: Invalid duration format fails validation naming offending key")
    void invalidDurationFormatFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.creeper.cooldown", "invalid-duration");

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.creeper.cooldown");
    }
}
