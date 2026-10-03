package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CalmEffectsConfigTest {
    @TempDir Path folder;
    private static final List<String> NEW = List.of("flowers", "clear-sky", "ambient-particles", "music", "warm-phrases", "glowing-animals");
    private YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    @Test void legacyConfigAdoptsSixEffectsWithoutChangingOwnerSettings() throws Exception {
        var yaml = shipped();
        for (String id : NEW) yaml.set("effects.serenity." + id, null);
        yaml.set("effects.serenity.dawn.enabled", false);
        yaml.set("effects.serenity.apparition.duration-ticks", 250);
        yaml.save(folder.resolve("config.yml").toFile());
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(),
                new MessageRegistry(folder.toFile(), "es", null), Runnable::run, null);
        manager.initialize();
        assertThat(manager.config().effects().serenity().rules().get("dawn").enabled()).isFalse();
        assertThat(manager.config().effects().serenity().animalDuration()).isEqualTo(250);
        for (String id : NEW) {
            String key = "effects.serenity." + id + ".enabled";
            assertThat(manager.get(key)).isEqualTo("true");
            manager.set(key, "false");
            assertThat(manager.config().effects().serenity().rules().get(id).enabled()).isFalse();
        }
        manager.set("effects.serenity.flowers.types", "[allium, blue_orchid]");
        assertThat(manager.config().effects().serenity().flowers().types()).containsExactly("allium", "blue_orchid");
        manager.set("effects.serenity.music.keys", "[minecraft:music.overworld.cherry_grove]");
        assertThat(manager.config().effects().serenity().music().keys()).containsExactly("minecraft:music.overworld.cherry_grove");
        manager.set("effects.serenity.warm-phrases.lines", "['&a{player}', '&b...']");
        manager.reload();
        assertThat(manager.snapshot().messages().lineKeys("effects.serenity.warm-phrases.lines")).hasSize(2);
        assertThat(manager.config().effects().serenity().flowers().types()).containsExactly("allium", "blue_orchid");
    }

    @Test void rejectsInvalidListsCountsDurationsAndRangesByPath() {
        Map<String, Object> badLeaves = Map.ofEntries(
                Map.entry("flowers.types", List.of("wither_rose")), Map.entry("flowers.range-blocks", 9),
                Map.entry("flowers.count", 33), Map.entry("flowers.duration-ticks", 0),
                Map.entry("clear-sky.duration-ticks", 1201), Map.entry("ambient-particles.count", 65),
                Map.entry("ambient-particles.radius-blocks", Double.NaN), Map.entry("ambient-particles.duration-ticks", 201),
                Map.entry("music.keys", List.of("minecraft:entity.creeper.primed")), Map.entry("music.volume", 2),
                Map.entry("music.duration-ticks", 1201), Map.entry("warm-phrases.duration-ticks", 0),
                Map.entry("glowing-animals.range-blocks", Double.POSITIVE_INFINITY),
                Map.entry("glowing-animals.count", 0), Map.entry("glowing-animals.duration-ticks", 401));
        for (var bad : badLeaves.entrySet()) {
            var yaml = shipped();
            String key = "effects.serenity." + bad.getKey();
            yaml.set(key, bad.getValue());
            assertThatThrownBy(() -> SerenityEffectsConfig.load(yaml)).hasMessageContaining(key);
        }
        for (String id : NEW) for (String leaf : List.of("enabled", "minimum-serenity", "cooldown-ticks", "session-cap")) {
            var yaml = shipped();
            String key = "effects.serenity." + id + "." + leaf;
            yaml.set(key, "bad");
            assertThatThrownBy(() -> SerenityEffectsConfig.load(yaml)).hasMessageContaining(key);
        }
    }

    @Test void bothLanguagesHaveValidatedRotatingPhrasesAndSwitchNames() {
        for (String lang : List.of("en", "es")) {
            var messages = MessageRegistry.loadMessagesSnapshot(folder.toFile(), lang, null);
            var keys = messages.lineKeys("effects.serenity.warm-phrases.lines");
            assertThat(keys).hasSize(4);
            CatalogueLines.validateSnapshot(messages, 160);
            for (String key : keys) assertThat(messages.resolveRaw(key, new java.util.HashSet<>(), null)).isNotBlank();
            for (String id : NEW) assertThat(messages.activeMessages()).containsKey("features.name.effects.serenity." + id);
        }
        assertThatThrownBy(() -> CatalogueLines.validateList("effects.serenity.warm-phrases.lines", List.of(), 160))
                .isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> CatalogueLines.validateList("effects.serenity.warm-phrases.lines", List.of("one\ntwo"), 160))
                .isInstanceOf(ConfigValidationException.class);
    }
}
