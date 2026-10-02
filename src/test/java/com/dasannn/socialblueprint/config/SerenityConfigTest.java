package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SerenityConfigTest {
    @TempDir Path folder;
    private YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    @Test void eachCurveLeafRejectsInvalidNumbersByExactPath() {
        for (String leaf : List.of("ceiling", "active-hours-to-ceiling", "idle-timeout-seconds")) {
            String key = "psychosis.serenity." + leaf;
            for (Object bad : List.of(0, -1, Double.NaN, Double.POSITIVE_INFINITY, "invalid")) {
                var yaml = shipped(); yaml.set(key, bad);
                assertThatThrownBy(() -> PsychosisConfigSection.load(yaml)).isInstanceOf(ConfigValidationException.class).hasMessageContaining(key);
            }
        }
    }

    @Test void allNewLeavesAreRegisteredAndLiveEditableWithAtomicBounds() {
        var messages = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(), messages, Runnable::run, null);
        manager.initialize();
        var yaml = shipped();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || !key.startsWith("psychosis.serenity.") && !key.startsWith("effects.serenity.")) continue;
            assertThat(manager.get(key)).as(key).isNotNull();
            assertThat(ConfigManager.extractLeafValues(yaml)).containsKey(key);
            manager.set(key, yaml.get(key).toString());
        }
        manager.set("psychosis.serenity.ceiling", "50");
        manager.set("sounds.serenity-clean", "[{key: 'minecraft:block.water.ambient', volume: 0.2, pitch: 1.0, category: AMBIENT, delay: 0}]");
        assertThat(manager.snapshot().config().sounds().get("serenity-clean").layers()).hasSize(1);
        manager.set("sounds.serenity-clean", "[{key: 'minecraft:block.water.ambient', volume: 0.2, pitch: 1.0, category: AMBIENT, delay: 20}]");
        assertThat(manager.snapshot().config().psychosis().serenity().ceiling()).isEqualTo(50);
        var before = manager.snapshot();
        assertThatThrownBy(() -> manager.set("effects.serenity.dawn.minimum-serenity", "51"))
                .hasMessageContaining("effects.serenity.dawn.minimum-serenity");
        assertThat(manager.snapshot()).isSameAs(before);
        assertThatThrownBy(() -> manager.set("effects.serenity.source-less-sounds.playback-ticks", "100"))
                .hasMessageContaining("effects.serenity.source-less-sounds.playback-ticks");
        for (var bad : Map.of("episodes.quiet-ticks", 0, "dawn.time-ticks", 24000, "particles.count", 0,
                "apparition.kind", "zombie", "dawn.duration-ticks", 101).entrySet()) {
            var invalid = shipped(); invalid.set("effects.serenity." + bad.getKey(), bad.getValue());
            assertThatThrownBy(() -> PluginConfig.load(invalid)).hasMessageContaining("effects.serenity." + bad.getKey());
        }
    }

    @Test void bothPinnedLanguagesLabelTheSameMetric() {
        for (String language : List.of("en", "es")) {
            var registry = new MessageRegistry(folder.toFile(), language, null);
            var snapshot = registry.snapshot();
            var neutral = PlayerSocialView.neutral(PlayerId.of(UUID.randomUUID()), "Peaceful", snapshot.config().tiers().ladder());
            assertThat(registry.psychosisLabel(snapshot, neutral)).isEqualTo(registry.getRaw(snapshot, "psychosis.neutral.name"));
            assertThat(registry.serenityValue(neutral)).isEqualTo("0.0");
            var serene = new PlayerSocialView(neutral.playerId(), neutral.name(), -100, neutral.tier(),
                    ConfidenceLevel.UNKNOWN, PsychosisLevel.SERENITY, 0, 43.75);
            assertThat(registry.psychosisLabel(snapshot, serene)).isEqualTo(registry.getRaw(snapshot, "psychosis.neutral.name"));
            assertThat(registry.serenityValue(serene)).isEqualTo("43.8");
            var mad = new PlayerSocialView(neutral.playerId(), neutral.name(), -100, neutral.tier(),
                    ConfidenceLevel.UNKNOWN, PsychosisLevel.HIGH, 0, 2);
            assertThat(registry.psychosisLabel(snapshot, mad)).isEqualTo(registry.getRaw(snapshot, "psychosis.high"));
            assertThat(registry.serenityValue(mad)).isEqualTo("0.0");
            assertThat(snapshot.messages().bundledActiveMessages()).containsKeys("status.profile-serenity", "chat.hover-serenity",
                    "gui.prompt-give-reason", "honor.reason-too-short");
            assertThat(registry.getRaw(snapshot, "psychosis.serenity.name")).isEqualTo(language.equals("en") ? "Serenity" : "Serenidad");
            assertThat(serene.status()).isEqualTo(-100);
            assertThat(serene.confidence()).isEqualTo(ConfidenceLevel.UNKNOWN);
        }
    }

    @Test void upgradeMergesOnlyMissingSerenitySettingsAndMessages() throws Exception {
        var yaml = shipped();
        yaml.set("psychosis.serenity", null);
        yaml.set("effects.serenity", null);
        yaml.set("sounds.serenity-clean", null);
        yaml.set("psychosis.window", "90h");
        yaml.save(folder.resolve("config.yml").toFile());
        for (String language : List.of("en", "es")) {
            var texts = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    getClass().getClassLoader().getResourceAsStream("messages_" + language + ".yml"), StandardCharsets.UTF_8));
            texts.set("psychosis.neutral", null);
            texts.set("psychosis.serenity", null);
            texts.set("psychosis.low", "owner-low");
            texts.save(folder.resolve("messages_" + language + ".yml").toFile());
        }
        var registry = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(), registry, Runnable::run, null);
        manager.initialize();
        assertThat(manager.snapshot().config().psychosis().window()).isEqualTo(java.time.Duration.ofHours(90));
        assertThat(manager.snapshot().config().psychosis().serenity()).isEqualTo(SerenityConfig.DEFAULT);
        assertThat(manager.snapshot().config().sounds().get("serenity-clean").layers()).hasSize(3);
        for (String language : List.of("en", "es")) {
            var upgraded = YamlConfiguration.loadConfiguration(folder.resolve("messages_" + language + ".yml").toFile());
            assertThat(upgraded.getString("psychosis.low")).isEqualTo("owner-low");
            assertThat(upgraded.contains("psychosis.neutral.name")).isTrue();
            assertThat(upgraded.contains("psychosis.serenity.name")).isTrue();
            assertThat(upgraded.contains("psychosis.serenity.detail")).isTrue();
        }
    }
}
