package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRegistryTest {

    @TempDir
    File tempDir;

    private List<LogRecord> loggedRecords;
    private Logger testLogger;

    @BeforeEach
    void setUp() {
        loggedRecords = new ArrayList<>();
        testLogger = Logger.getLogger("MessageRegistryTest-" + System.nanoTime());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                loggedRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @Test
    @DisplayName("T-032d / DoD 4: Switching language between es and en changes tier display names")
    void switchingLanguageChangesTierNames() {
        MessageRegistry registry = new MessageRegistry(tempDir, "es", testLogger);

        // Verify Spanish tier names (T-032d)
        assertThat(registry.tierName(Tier.CRIMINAL)).isEqualTo("Criminal");
        assertThat(registry.tierName(Tier.FORAJIDO)).isEqualTo("Forajido");
        assertThat(registry.tierName(Tier.DELINCUENTE)).isEqualTo("Delincuente");
        assertThat(registry.tierName(Tier.TEMERARIO)).isEqualTo("Temerario");
        assertThat(registry.tierName(Tier.PARTICULAR)).isEqualTo("Particular");
        assertThat(registry.tierName(Tier.AFABLE)).isEqualTo("Afable");
        assertThat(registry.tierName(Tier.HONORABLE)).isEqualTo("Honorable");
        assertThat(registry.tierName(Tier.INSIGNE)).isEqualTo("Insigne");
        assertThat(registry.tierName(Tier.ILUSTRE)).isEqualTo("Ilustre");

        // Switch to English
        registry.setLanguage("en");

        // Verify English tier names with escalating tone (T-032d)
        assertThat(registry.tierName(Tier.CRIMINAL)).isEqualTo("Criminal");
        assertThat(registry.tierName(Tier.FORAJIDO)).isEqualTo("Outlaw");
        assertThat(registry.tierName(Tier.DELINCUENTE)).isEqualTo("Delinquent");
        assertThat(registry.tierName(Tier.TEMERARIO)).isEqualTo("Reckless");
        assertThat(registry.tierName(Tier.PARTICULAR)).isEqualTo("Citizen");
        assertThat(registry.tierName(Tier.AFABLE)).isEqualTo("Affable");
        assertThat(registry.tierName(Tier.HONORABLE)).isEqualTo("Honorable");
        assertThat(registry.tierName(Tier.INSIGNE)).isEqualTo("Distinguished");
        assertThat(registry.tierName(Tier.ILUSTRE)).isEqualTo("Illustrious");
    }

    @Test
    @DisplayName("T-032b: Key missing from selected language falls back to other language and logs warning naming key ONCE")
    void missingKeyFallsBackAndWarnsOnce() {
        Map<String, String> spanish = Map.of(
                "prefix", "&8[&bSB&8] ",
                "common.hello", "Hola"
        );
        Map<String, String> english = Map.of(
                "prefix", "&8[&bSB&8] ",
                "common.hello", "Hello",
                "honor.cost.notice", "Cost is 500"
        );

        MessageRegistry registry = MessageRegistry.fromMaps(spanish, english, "es", testLogger);

        // honor.cost.notice is missing in Spanish, present in English fallback
        String resolved1 = registry.getRaw("honor.cost.notice");
        assertThat(resolved1).isEqualTo("Cost is 500");

        // Verify warning was logged naming the key
        assertThat(loggedRecords).hasSize(1);
        LogRecord record1 = loggedRecords.getFirst();
        assertThat(record1.getMessage()).contains("honor.cost.notice").contains("es").contains("en");

        // Second lookup should NOT log another warning (warns ONCE per T-032b)
        String resolved2 = registry.getRaw("honor.cost.notice");
        assertThat(resolved2).isEqualTo("Cost is 500");
        assertThat(loggedRecords).hasSize(1);
    }

    @Test
    @DisplayName("T-032b: Completely missing key never reaches player as raw key (e.g. messages.honor.cost)")
    void completelyMissingKeyNeverReachesPlayer() {
        Map<String, String> spanish = Map.of("prefix", "&8[&bSB&8] ");
        Map<String, String> english = Map.of("prefix", "&8[&bSB&8] ");

        MessageRegistry registry = MessageRegistry.fromMaps(spanish, english, "es", testLogger);

        String result = registry.getRaw("messages.honor.cost");
        assertThat(result).isNotEqualTo("messages.honor.cost");
        assertThat(result).isEmpty();

        Component rendered = registry.render("messages.honor.cost");
        assertThat(rendered).isEqualTo(Component.empty());
    }

    @Test
    @DisplayName("T-032: Placeholder substitution with legacy and hex colours")
    void placeholderSubstitutionWithColors() {
        MessageRegistry registry = new MessageRegistry(tempDir, "en", testLogger);

        Component rendered = registry.render("commands.config.get", Map.of(
                "key", "honor.cost",
                "value", "500.0"
        ));
        String serialized = ColorParser.serialize(rendered);
        assertThat(serialized).contains("&bhonor.cost").contains("&f500.0");
    }

    @Test
    @DisplayName("T-032a: Extracts messages_es.yml and messages_en.yml to data folder on first run")
    void extractsMessagesFilesOnFirstRun() {
        MessageRegistry registry = new MessageRegistry(tempDir, "en", testLogger);

        File esFile = new File(tempDir, "messages_es.yml");
        File enFile = new File(tempDir, "messages_en.yml");

        assertThat(esFile).exists();
        assertThat(enFile).exists();
    }
}
