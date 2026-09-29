package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.domain.Tier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidationTest {

    @Test
    @DisplayName("T-036 / DoD 3: Shipped config.yml loads cleanly and resolves all nine tiers")
    void shippedConfigLoadsCleanly() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertThat(stream).isNotNull();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        PluginConfig config = PluginConfig.load(yaml);

        assertThat(config).isNotNull();
        assertThat(config.language()).isIn("en", "es");
        assertThat(config.chatPrefix()).contains("SocialBlueprint");

        // Verify all 9 tiers exist with correct prefixes
        TiersConfig tiers = config.tiers();
        assertThat(tiers.prefix(Tier.CRIMINAL)).isEqualTo("&7[&4||||&7]");
        assertThat(tiers.prefix(Tier.FORAJIDO)).isEqualTo("&7[&c|||&7]");
        assertThat(tiers.prefix(Tier.DELINCUENTE)).isEqualTo("&7[&c||&7]");
        assertThat(tiers.prefix(Tier.TEMERARIO)).isEqualTo("&7[&c|&7]");
        assertThat(tiers.prefix(Tier.PARTICULAR)).isEqualTo("&7[&f|&7]");
        assertThat(tiers.prefix(Tier.AFABLE)).isEqualTo("&7[&a|&7]");
        assertThat(tiers.prefix(Tier.HONORABLE)).isEqualTo("&7[&a||&7]");
        assertThat(tiers.prefix(Tier.INSIGNE)).isEqualTo("&7[&a|||&7]");
        assertThat(tiers.prefix(Tier.ILUSTRE)).isEqualTo("&7[&b||||&7]");

        // Verify signed thresholds
        assertThat(tiers.get(Tier.CRIMINAL).threshold()).isEqualTo(-30);
        assertThat(tiers.get(Tier.FORAJIDO).threshold()).isEqualTo(-20);
        assertThat(tiers.get(Tier.DELINCUENTE).threshold()).isEqualTo(-10);
        assertThat(tiers.get(Tier.TEMERARIO).threshold()).isEqualTo(-1);
        assertThat(tiers.get(Tier.PARTICULAR).threshold()).isEqualTo(0);
        assertThat(tiers.get(Tier.AFABLE).threshold()).isEqualTo(5);
        assertThat(tiers.get(Tier.HONORABLE).threshold()).isEqualTo(15);
        assertThat(tiers.get(Tier.INSIGNE).threshold()).isEqualTo(30);
        assertThat(tiers.get(Tier.ILUSTRE).threshold()).isEqualTo(50);

        // Verify domain ladder resolution
        assertThat(tiers.ladder().resolve(Status.of(-100))).isEqualTo(Tier.CRIMINAL);
        assertThat(tiers.ladder().resolve(Status.of(-30))).isEqualTo(Tier.CRIMINAL);
        assertThat(tiers.ladder().resolve(Status.of(-25))).isEqualTo(Tier.FORAJIDO);
        assertThat(tiers.ladder().resolve(Status.of(-15))).isEqualTo(Tier.DELINCUENTE);
        assertThat(tiers.ladder().resolve(Status.of(-5))).isEqualTo(Tier.TEMERARIO);
        assertThat(tiers.ladder().resolve(Status.ZERO)).isEqualTo(Tier.PARTICULAR);
        assertThat(tiers.ladder().resolve(Status.of(4))).isEqualTo(Tier.PARTICULAR);
        assertThat(tiers.ladder().resolve(Status.of(5))).isEqualTo(Tier.AFABLE);
        assertThat(tiers.ladder().resolve(Status.of(15))).isEqualTo(Tier.HONORABLE);
        assertThat(tiers.ladder().resolve(Status.of(30))).isEqualTo(Tier.INSIGNE);
        assertThat(tiers.ladder().resolve(Status.of(50))).isEqualTo(Tier.ILUSTRE);
        assertThat(tiers.ladder().resolve(Status.of(100))).isEqualTo(Tier.ILUSTRE);

        // Verify Confidence settings
        assertThat(config.confidence().halfLife()).isEqualTo(Duration.ofDays(30));
        assertThat(config.confidence().lowThreshold()).isEqualTo(1.0);
        assertThat(config.confidence().establishedThreshold()).isEqualTo(5.0);
        assertThat(config.confidence().highThreshold()).isEqualTo(15.0);

        // Verify Psychosis settings
        assertThat(config.psychosis().window()).isEqualTo(Duration.ofHours(24));
        assertThat(config.psychosis().mediumThreshold()).isEqualTo(2);
        assertThat(config.psychosis().highThreshold()).isEqualTo(5);
        assertThat(config.psychosis().extremeThreshold()).isEqualTo(10);

        // Verify Honor settings
        assertThat(config.honor().cost()).isEqualTo(500.0);
        assertThat(config.honor().multipliers()).containsExactly(1.0, 1.5, 2.0, 3.0);
        assertThat(config.honor().window()).isEqualTo(Duration.ofHours(1));
        assertThat(config.honor().cooldownPerPair()).isEqualTo(Duration.ofHours(24));
        assertThat(config.honor().maxPerTarget()).isEqualTo(3);
    }

    @Test
    @DisplayName("T-031 / DoD 2: Baseline positive thresholds on negative tiers fails enable, naming the offending key")
    void baselinePositiveThresholdsFailValidation() {
        YamlConfiguration yaml = loadValidYaml();
        // Set baseline positive thresholds on negative tiers
        yaml.set("tiers.tier-4.threshold", 30);
        yaml.set("tiers.tier-3.threshold", 20);
        yaml.set("tiers.tier-2.threshold", 10);
        yaml.set("tiers.tier-1.threshold", 1);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier-4.threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier-4.threshold"));
    }

    @Test
    @DisplayName("T-031: Out of order negative thresholds fail naming the offending key")
    void outOfOrderNegativeThresholdsFail() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("tiers.tier-2.threshold", -1); // Delincuente equal to Temerario (-1)

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier-2.threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier-2.threshold"));
    }

    @Test
    @DisplayName("T-031: Out of order positive thresholds fail naming the offending key")
    void outOfOrderPositiveThresholdsFail() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("tiers.tier2.threshold", 5); // Honorable equal to Afable (5)

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier2.threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier2.threshold"));
    }

    @Test
    @DisplayName("T-031: Neutral tier threshold not equal to 0 fails naming the key")
    void neutralTierThresholdNotZeroFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("tiers.tier0.threshold", 2);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier0.threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier0.threshold"));
    }

    @Test
    @DisplayName("T-031: Missing tier section fails naming the missing key")
    void missingTierFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("tiers.tier4", null);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier4")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier4"));
    }

    @Test
    @DisplayName("T-031: Missing tier prefix fails naming the key")
    void missingTierPrefixFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("tiers.tier-3.prefix", null);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier-3.prefix")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier-3.prefix"));
    }

    @Test
    @DisplayName("T-031: Negative cooldown fails naming the offending key")
    void negativeCooldownFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("honor.cooldown-per-pair", "-5h");

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("honor.cooldown-per-pair")
                .matches(e -> ((ConfigValidationException) e).key().equals("honor.cooldown-per-pair"));
    }

    @Test
    @DisplayName("T-031: Missing required keys in root or sections fails naming the key")
    void missingRequiredKeysFail() {
        YamlConfiguration yaml1 = loadValidYaml();
        yaml1.set("language", null);
        assertThatThrownBy(() -> PluginConfig.load(yaml1))
                .isInstanceOf(ConfigValidationException.class)
                .matches(e -> ((ConfigValidationException) e).key().equals("language"));

        YamlConfiguration yaml2 = loadValidYaml();
        yaml2.set("chat-prefix", null);
        assertThatThrownBy(() -> PluginConfig.load(yaml2))
                .isInstanceOf(ConfigValidationException.class)
                .matches(e -> ((ConfigValidationException) e).key().equals("chat-prefix"));

        YamlConfiguration yaml3 = loadValidYaml();
        yaml3.set("honor.cost", null);
        assertThatThrownBy(() -> PluginConfig.load(yaml3))
                .isInstanceOf(ConfigValidationException.class)
                .matches(e -> ((ConfigValidationException) e).key().equals("honor.cost"));

        YamlConfiguration yaml4 = loadValidYaml();
        yaml4.set("psychosis.window", null);
        assertThatThrownBy(() -> PluginConfig.load(yaml4))
                .isInstanceOf(ConfigValidationException.class)
                .matches(e -> ((ConfigValidationException) e).key().equals("psychosis.window"));

        YamlConfiguration yaml5 = loadValidYaml();
        yaml5.set("confidence.half-life", null);
        assertThatThrownBy(() -> PluginConfig.load(yaml5))
                .isInstanceOf(ConfigValidationException.class)
                .matches(e -> ((ConfigValidationException) e).key().equals("confidence.half-life"));
    }

    @Test
    @DisplayName("T-031: Psychosis thresholds out of order fail naming the offending key")
    void psychosisThresholdsOutOfOrderFail() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("psychosis.high-threshold", 2); // equal to medium (2)

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("psychosis.high-threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("psychosis.high-threshold"));
    }

    @Test
    @DisplayName("T-031: Confidence thresholds out of order fail naming the offending key")
    void confidenceThresholdsOutOfOrderFail() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("confidence.established-threshold", 0.5); // less than low (1.0)

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("confidence.established-threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("confidence.established-threshold"));
    }

    @Test
    @DisplayName("T-031: Unsupported language code fails naming the key")
    void unsupportedLanguageFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("language", "fr");

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("language")
                .matches(e -> ((ConfigValidationException) e).key().equals("language"));
    }

    @Test
    @DisplayName("T-031: Missing required permission action mapping fails naming the key")
    void missingPermissionMappingFails() {
        YamlConfiguration yaml = loadValidYaml();
        yaml.set("permissions.admin-config", null);

        assertThatThrownBy(() -> PluginConfig.load(yaml))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("permissions.admin-config")
                .matches(e -> ((ConfigValidationException) e).key().equals("permissions.admin-config"));
    }

    private static YamlConfiguration loadValidYaml() {
        InputStream stream = ConfigValidationTest.class.getClassLoader().getResourceAsStream("config.yml");
        assertThat(stream).isNotNull();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
