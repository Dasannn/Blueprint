package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectsConfigValidationTest {

    private YamlConfiguration loadValidYaml() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertThat(stream).isNotNull();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("T-070 / SB-043: Missing effects section loads clean defaults")
    void missingEffectsSectionUsesDefaults() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects", null);

        PluginConfig config = PluginConfig.load(yaml);
        assertThat(config.effects()).isNotNull();
        assertThat(config.effects().threshold()).isEqualTo(-10);
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
    @DisplayName("T-072: Zero or negative duration-ticks on silverfish fails validation naming offending key")
    void zeroOrNegativeDurationTicksFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("effects.silverfish.duration-ticks", 0);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.silverfish.duration-ticks")
                .matches(e -> "effects.silverfish.duration-ticks".equals(((ConfigValidationException) e).key()));

        yaml.set("effects.silverfish.duration-ticks", -10);
        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("effects.silverfish.duration-ticks");
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
