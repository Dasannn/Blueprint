package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.*;

class MindLevelPhrasesConfigTest {
    @TempDir Path folder;

    private YamlConfiguration bundled(String resource) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(resource), StandardCharsets.UTF_8));
    }

    @Test void upgradeAddsSettingAndListsAndPreservesOwnerEdits() throws Exception {
        var yaml = bundled("config.yml");
        yaml.set("language", "en");
        yaml.set("mind.level-phrases", null);
        yaml.save(folder.resolve("config.yml").toFile());
        var oldMessages = bundled("messages_en.yml");
        oldMessages.set("mind-levels", null);
        oldMessages.save(folder.resolve("messages_en.yml").toFile());
        var messages = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(), messages,
                Runnable::run, () -> "2.0.4", null);
        manager.initialize();
        assertThat(manager.config().levelPhrasesEnabled()).isTrue();
        assertThat(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile())
                .getBoolean("mind.level-phrases.enabled")).isTrue();
        assertThat(manager.editableKeys(manager.snapshot())).contains("mind.level-phrases.enabled");
        for (String key : CatalogueLines.LEVEL_PHRASES)
            assertThat(manager.snapshot().messages().lineKeys(key)).hasSize(4);
        manager.set("mind.level-phrases.enabled", "false");
        assertThat(manager.config().levelPhrasesEnabled()).isFalse();
        assertThat(manager.config().withLanguage("es").levelPhrasesEnabled()).isFalse();
        var before = manager.snapshot();
        assertThatThrownBy(() -> manager.set("mind.level-phrases.enabled", "sometimes"))
                .hasMessageContaining("mind.level-phrases.enabled");
        assertThat(manager.snapshot()).isSameAs(before);
        String key = "mind-levels.psychosis-fell.neutral.lines";
        manager.set(key, "[]");
        manager.reload();
        assertThat(manager.config().levelPhrasesEnabled()).isFalse();
        assertThat(manager.snapshot().messages().lineKeys(key)).isEmpty();
    }

    @Test void optionalListsNeverUseFallbackOrWarnWhenMissingOrEmpty() {
        String key = "mind-levels.psychosis-rose.low.lines";
        Map<String, String> defaults = MessageRegistry.flattenKeys(bundled("messages_en.yml"));
        for (var active : java.util.List.of(Map.<String, String>of(), Map.of(key, ""))) {
            var snapshot = new MessagesSnapshot("es", "en", active, defaults, defaults, defaults);
            var warned = new java.util.HashSet<String>();
            assertThat(snapshot.lineKeys(key)).isEmpty();
            assertThat(snapshot.resolveRaw(key + ".0", warned, Logger.getLogger("OptionalThoughts"))).isEmpty();
            assertThat(warned).isEmpty();
        }
    }

    @Test void bothLanguagesLoadEditableListsAndIndexedKeys() {
        for (String language : Set.of("en", "es")) {
            var map = MessageRegistry.flattenKeys(bundled("messages_" + language + ".yml"));
            var snapshot = new MessagesSnapshot(language, "en", map, Map.of(), Map.of(), Map.of());
            CatalogueLines.validateSnapshot(snapshot, 160);
            for (String key : CatalogueLines.LEVEL_PHRASES) {
                assertThat(snapshot.lineKeys(key)).containsExactly(key + ".0", key + ".1", key + ".2", key + ".3");
                for (String indexed : snapshot.lineKeys(key))
                    assertThat(snapshot.resolveRaw(indexed, new java.util.HashSet<>(), null)).isNotBlank();
            }
        }
    }
}
