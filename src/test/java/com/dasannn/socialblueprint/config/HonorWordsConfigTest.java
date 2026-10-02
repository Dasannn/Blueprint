package com.dasannn.socialblueprint.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorWordsConfigTest {
    @TempDir File folder;

    @Test void upgradeRetiresSkipInBothLanguagesAndListsAndMinimumAreLiveValidated() throws Exception {
        File config = new File(folder, "config.yml");
        try (var input = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            Files.copy(input, config.toPath());
        }
        for (String language : List.of("en", "es")) {
            File messages = new File(folder, "messages_" + language + ".yml");
            Files.writeString(messages.toPath(), "gui:\n  reason-skip-word: '-'\n  prompt-give-reason: 'Old prompt {skip}'\n", StandardCharsets.UTF_8);
        }
        var logger = Logger.getLogger("HonorWordsConfigTest");
        var manager = new ConfigManager(config, new MessageRegistry(folder, "en", logger), Runnable::run, logger);
        manager.initialize();
        for (String language : List.of("en", "es")) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(folder, "messages_" + language + ".yml"));
            assertThat(yaml.contains("gui.reason-skip-word")).isFalse();
            assertThat(yaml.getString("gui.prompt-give-reason")).doesNotContain("{skip}");
        }
        assertThat(manager.isEditableKey("gui.reason-skip-word")).isFalse();
        assertThat(manager.snapshot().config().honor().reasonMinLength()).isEqualTo(3);
        manager.set("honor.reason.min-length", "5");
        assertThat(manager.snapshot().config().honor().reasonMinLength()).isEqualTo(5);
        for (String invalid : List.of("0", "101", "3.5", "NaN"))
            assertThatThrownBy(() -> manager.set("honor.reason.min-length", invalid)).isInstanceOf(ConfigValidationException.class);
        manager.set("chat-filter.words.es", "[imbécil]");
        manager.set("chat-filter.words.en", "[idiot]");
        assertThat(manager.snapshot().config().chatFilter().apply("IMBECIL idiot", "bobba")).isEqualTo("bobba bobba");
        for (String invalid : List.of("['']", "[null]", "[5]", "['two words']", "oops", "[idiot, '']"))
            assertThatThrownBy(() -> manager.set("chat-filter.words.en", invalid)).isInstanceOf(ConfigValidationException.class);
        manager.set("chat-filter.enabled", "false");
        assertThat(manager.snapshot().config().chatFilter().apply("idiot", "bobba")).isEqualTo("idiot");
        manager.reload();
        assertThat(manager.snapshot().config().honor().reasonMinLength()).isEqualTo(5);
        assertThat(manager.snapshot().config().chatFilter().enabled()).isFalse();
        assertThat(manager.snapshot().config().permissions().node("admin-revoke")).isEqualTo("socialblueprint.admin.revoke");
    }
}
