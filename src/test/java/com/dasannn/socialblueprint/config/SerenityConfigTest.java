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
        for (String leaf : List.of("ceiling", "idle-timeout-seconds")) {
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
                "apparition.kinds", List.of("zombie"), "dawn.duration-ticks", 201).entrySet()) {
            var invalid = shipped(); invalid.set("effects.serenity." + bad.getKey(), bad.getValue());
            assertThatThrownBy(() -> PluginConfig.load(invalid)).hasMessageContaining("effects.serenity." + bad.getKey());
        }
    }

    @Test void dawnAllowsTwoHundredTicksAndApparitionAllowsFourHundred() {
        assertThat(SerenityEffectsConfig.defaults().dawnDuration()).isEqualTo(200);
        assertThat(SerenityEffectsConfig.defaults().animalDuration()).isEqualTo(300);
        assertThat(PluginConfig.load(shipped()).effects().serenity().dawnDuration()).isEqualTo(200);
        assertThat(PluginConfig.load(shipped()).effects().serenity().animalDuration()).isEqualTo(300);
        for (int ticks : List.of(1, 60, 100, 101, 200)) {
            var yaml = shipped(); yaml.set("effects.serenity.dawn.duration-ticks", ticks);
            assertThat(SerenityEffectsConfig.load(yaml).dawnDuration()).isEqualTo(ticks);
        }
        for (int ticks : List.of(0, -1, 201)) {
            var yaml = shipped(); yaml.set("effects.serenity.dawn.duration-ticks", ticks);
            assertThatThrownBy(() -> SerenityEffectsConfig.load(yaml)).hasMessageContaining("dawn.duration-ticks");
        }
        var yaml = shipped(); yaml.set("effects.serenity.apparition.duration-ticks", 401);
        assertThatThrownBy(() -> SerenityEffectsConfig.load(yaml)).hasMessageContaining("apparition.duration-ticks");
    }

    @Test void apparitionBoundsAndFollowLeavesAreValidated() {
        var defaults = SerenityEffectsConfig.defaults();
        assertThat(defaults.followUpdateTicks()).isEqualTo(5);
        assertThat(defaults.followDistance()).isEqualTo(6);
        for (int ticks : List.of(1, 60, 100, 300, 400)) {
            var yaml = shipped(); yaml.set("effects.serenity.apparition.duration-ticks", ticks);
            assertThat(SerenityEffectsConfig.load(yaml).animalDuration()).isEqualTo(ticks);
        }
        for (String leaf : List.of("duration-ticks", "follow-update-ticks", "follow-distance-blocks")) {
            List<?> invalid = switch (leaf) {
                case "duration-ticks" -> List.of(0, -1, 401, 1.5, "bad");
                case "follow-update-ticks" -> List.of(0, -1, 21, 1.5, "bad");
                default -> List.of(0, 4, Double.NaN, Double.POSITIVE_INFINITY, "bad");
            };
            for (Object bad : invalid) {
                var yaml = shipped(); yaml.set("effects.serenity.apparition." + leaf, bad);
                assertThatThrownBy(() -> SerenityEffectsConfig.load(yaml)).hasMessageContaining("apparition." + leaf);
            }
        }
    }

    @Test void durationUpgradeAdoptsOldDefaultsOnceAndPreservesCustomDurations() throws Exception {
        for (int old : List.of(60, 100, 75, 200, 400)) {
            Path data = java.nio.file.Files.createDirectory(folder.resolve("duration-" + old));
            var yaml = shipped(); yaml.set("effects.serenity.apparition.duration-ticks", old);
            yaml.save(data.resolve("config.yml").toFile());
            // A server which already received T-211 must still receive the follow defaults.
            java.nio.file.Files.writeString(data.resolve("serenity-defaults-v1.flag"), "already adopted");
            var registry = new MessageRegistry(data.toFile(), "en", null);
            var manager = new ConfigManager(data.resolve("config.yml").toFile(), registry, Runnable::run, null);
            manager.initialize();
            assertThat(manager.snapshot().config().effects().serenity().animalDuration())
                    .isEqualTo(old == 60 || old == 100 ? 300 : old);
            manager.set("effects.serenity.apparition.duration-ticks", "60");
            manager.reload();
            assertThat(manager.snapshot().config().effects().serenity().animalDuration()).isEqualTo(60);
            assertThat(data.resolve("serenity-follow-v1.flag")).exists();
        }
    }

    @Test void bothPinnedLanguagesLabelTheSameMetric() {
        for (String language : List.of("en", "es")) {
            var registry = new MessageRegistry(folder.toFile(), language, null);
            var snapshot = registry.snapshot();
            var neutral = PlayerSocialView.neutral(PlayerId.of(UUID.randomUUID()), "Peaceful", snapshot.config().tiers().ladder());
            assertThat(registry.mentalStateLine(snapshot, neutral, "status.profile-mental-state", false).key())
                    .isEqualTo("status.profile-mental-state-neutral");
            var serene = new PlayerSocialView(neutral.playerId(), neutral.name(), -100, neutral.tier(),
                    ConfidenceLevel.UNKNOWN, PsychosisLevel.SERENITY, 0, 43.75);
            var line = registry.mentalStateLine(snapshot, serene, "status.profile-mental-state", false);
            assertThat(line.key()).isEqualTo("status.profile-mental-state-serenity");
            assertThat(line.placeholders()).containsExactlyEntriesOf(Map.of("value", "43.8"));
            var mad = new PlayerSocialView(neutral.playerId(), neutral.name(), -100, neutral.tier(),
                    ConfidenceLevel.UNKNOWN, PsychosisLevel.HIGH, 0, 2);
            var madLine = registry.mentalStateLine(snapshot, mad, "status.profile-mental-state", false);
            assertThat(madLine.key()).isEqualTo("status.profile-mental-state-psychosis");
            assertThat(madLine.placeholders()).containsExactlyInAnyOrderEntriesOf(Map.of("psychosis", registry.getRaw(snapshot, "psychosis.high"), "value", "2"));
            assertThat(snapshot.messages().bundledActiveMessages()).containsKeys("status.profile-mental-state-serenity", "chat.hover-mental-state-serenity",
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
        assertThat(manager.legacyMindConversion().window()).isEqualTo(java.time.Duration.ofHours(90));
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

    @Test void upgradeRetiresBothLinesPreservesMappedColoursAndExplicitNewKeys() throws Exception {
        Map<String, String> aliases = Map.of(
                "chat.hover-psychosis", "chat.hover-mental-state-psychosis",
                "chat.hover-serenity", "chat.hover-mental-state-serenity",
                "status.profile-psychosis", "status.profile-mental-state-psychosis",
                "status.profile-serenity", "status.profile-mental-state-serenity");
        for (String language : List.of("en", "es")) {
            var texts = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    getClass().getClassLoader().getResourceAsStream("messages_" + language + ".yml"), StandardCharsets.UTF_8));
            for (var alias : aliases.entrySet()) {
                texts.set(alias.getValue(), null);
                texts.set(alias.getKey(), "&#123456&lowner label: &d&o{" + (alias.getKey().endsWith("serenity") ? "serenity" : "psychosis") + "}");
            }
            // An operator who already adopted a new key wins over its legacy alias.
            texts.set("chat.hover-mental-state-serenity", "&c{value}");
            texts.set("status.profile-contributors", "&a{contributors}");
            texts.save(folder.resolve("messages_" + language + ".yml").toFile());
        }
        var registry = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(), registry, Runnable::run, null);
        manager.initialize();
        for (String language : List.of("en", "es")) {
            var upgraded = YamlConfiguration.loadConfiguration(folder.resolve("messages_" + language + ".yml").toFile());            for (var alias : aliases.entrySet()) {
                assertThat(upgraded.contains(alias.getKey())).isFalse();
                if (!alias.getValue().equals("chat.hover-mental-state-serenity")) {
                    assertThat(upgraded.getString(alias.getValue())).startsWith("&#123456&l").contains("&d&o");
                    assertThat(upgraded.getString(alias.getValue())).doesNotContain("owner label");
                }
            }
            assertThat(upgraded.getString("chat.hover-mental-state-serenity")).isEqualTo("&c{value}");
            assertThat(upgraded.getString("status.profile-contributors")).isEqualTo("&a{contributors}");
        }
        String after = java.nio.file.Files.readString(folder.resolve("messages_en.yml"));
        manager.reload();
        assertThat(java.nio.file.Files.readString(folder.resolve("messages_en.yml"))).isEqualTo(after);
        assertThat(manager.isEditableKey("status.profile-psychosis")).isFalse();
        manager.set("status.profile-mental-state-serenity", "&b{value}");
        assertThat(manager.get("status.profile-mental-state-serenity")).isEqualTo("&b{value}");
    }
}
