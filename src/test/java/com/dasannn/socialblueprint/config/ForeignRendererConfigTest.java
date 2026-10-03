package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ForeignRendererConfigTest {
    @TempDir Path folder;

    @Test void missingLeavesUseDefaultsAndInvalidValuesNameTheirKey() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertThat(ForeignRendererConfig.load(yaml)).isEqualTo(ForeignRendererConfig.defaults());
        yaml.set("chat.foreign-renderer.mode", "leave");
        assertThat(ForeignRendererConfig.load(yaml).prefix()).isEqualTo("before-line");
        for (String leaf : List.of("mode", "prefix")) {
            String key = "chat.foreign-renderer." + leaf;
            for (Object value : List.of("invalid", "", 1, true, List.of("wrap"))) {
                YamlConfiguration invalid = new YamlConfiguration();
                invalid.set(key, value);
                assertThatThrownBy(() -> ForeignRendererConfig.load(invalid))
                        .isInstanceOf(ConfigValidationException.class).hasMessageContaining(key);
            }
        }
    }

    @Test void legacyUpgradeAndLiveEditsPersistAndInvalidEditsAreAtomic() throws Exception {
        Path file = folder.resolve("config.yml");
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            Files.copy(in, file);
        }
        YamlFileUpdater.removeLeafAndSave(file.toFile(), "chat.foreign-renderer");
        var messages = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(file.toFile(), messages, Runnable::run, null);
        manager.initialize();
        assertThat(manager.config().foreignRenderer()).isEqualTo(ForeignRendererConfig.defaults());
        assertThat(manager.get("chat.foreign-renderer.mode")).isEqualTo("wrap");
        assertThat(manager.get("chat.foreign-renderer.prefix")).isEqualTo("before-line");
        for (String leaf : List.of("mode", "prefix")) {
            String key = "chat.foreign-renderer." + leaf;
            assertThat(manager.isEditableKey(key)).isTrue();
            var before = manager.snapshot();
            String disk = Files.readString(file);
            assertThatThrownBy(() -> manager.set(key, "invalid")).hasMessageContaining(key);
            assertThat(manager.snapshot()).isSameAs(before);
            assertThat(Files.readString(file)).isEqualTo(disk);
        }
        manager.set("chat.foreign-renderer.mode", "leave");
        for (String placement : List.of("before-line", "display-name", "none")) {
            manager.set("chat.foreign-renderer.prefix", placement);
            manager.reload();
            var config = manager.config();
            var expected = new ForeignRendererConfig("leave", placement);
            assertThat(config.foreignRenderer()).isEqualTo(expected);
            assertThat(config.withLanguage("en").foreignRenderer()).isEqualTo(expected);
            assertThat(config.withChatPrefix(config.chatPrefix()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withEffects(config.effects()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withUpdate(config.update()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withLegacyImport(config.legacyImport()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withDecay(config.decay()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withKillPenalty(config.killPenalty()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withSounds(config.sounds()).foreignRenderer()).isEqualTo(expected);
            assertThat(config.withHistory(config.history()).foreignRenderer()).isEqualTo(expected);
        }
    }
}
