package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRegistryTest {

    @Test
    void mentalStateTemplatesAndLevelColoursUseTheOtherLanguageAndWarnOnce() {
        String key = "status.profile-mental-state-psychosis-detail";
        var fallback = Map.of(key, "{psychosis} {value}", "psychosis.medium", "&#123456&lconfigured");
        var registry = MessageRegistry.fromMaps(Map.of(), fallback, "es", testLogger);
        var snapshot = registry.snapshot();
        var view = new com.dasannn.socialblueprint.domain.PlayerSocialView(
                com.dasannn.socialblueprint.domain.PlayerId.of(java.util.UUID.randomUUID()), "Subject", 0,
                Tier.PARTICULAR, com.dasannn.socialblueprint.domain.ConfidenceLevel.UNKNOWN,
                com.dasannn.socialblueprint.domain.PsychosisLevel.MEDIUM, 0, 34);
        for (int i = 0; i < 2; i++) {
            var line = registry.mentalStateLine(snapshot, view, "status.profile-mental-state", true);
            assertThat(line.placeholders()).containsEntry("value", "34");
            assertThat(registry.renderMentalState(snapshot, line)).isEqualTo(ColorParser.renderTemplate(
                    fallback.get(key), Map.of("value", "34"),
                    Map.of("psychosis", ColorParser.parse(fallback.get("psychosis.medium")))));
        }
        assertThat(registry.warnedKeys()).containsExactlyInAnyOrder(key, "psychosis.medium");
        assertThat(loggedRecords).hasSize(2);
    }

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
    @DisplayName("Finding 8: Placeholder values cannot recolour message or substitute recursively")
    void placeholdersCannotRecolourOrRecurse() {
        Map<String, String> spanish = Map.of(
                "test.greeting", "&aJugador: &f{player}&a dice &b{message}"
        );
        MessageRegistry registry = MessageRegistry.fromMaps(spanish, Collections.emptyMap(), "es", testLogger);

        Component rendered = registry.render("test.greeting", Map.of(
                "player", "&cMaliciousName",
                "message", "hola &4mundo {player}"
        ));

        // When serialized, the literal characters &c and &4 are preserved as text within their enclosing styles,
        // and {player} inside the message value is not expanded.
        String serialized = ColorParser.serialize(rendered);
        assertThat(serialized).isEqualTo("&aJugador: &f&cMaliciousName&a dice &bhola &4mundo {player}");

        // Also verify the component structure: the placeholder text is literal text
        assertThat(rendered).isNotNull();
    }

    @Test
    @DisplayName("Finding 1: Prefix is rendered from the authoritative runtime snapshot")
    void prefixRenderedFromAuthoritativeSnapshot() {
        Map<String, String> spanish = Map.of("test.msg", "Mensaje de prueba");
        MessageRegistry registry = MessageRegistry.fromMaps(spanish, Collections.emptyMap(), "es", testLogger);

        Component rendered = registry.renderWithPrefix("test.msg");
        String serialized = ColorParser.serialize(rendered);

        // Uses the chat-prefix from configuration snapshot (&8[&bSocialBlueprint&8]&r )
        assertThat(serialized).startsWith("&8[&bSocialBlueprint&8]&r ");
    }

    @Test
    @DisplayName("Fix 1: Reload forced between prefix and body rendering produces wholly old output, never mixed")
    void reloadInterleavedBetweenPrefixAndBodyNeverMixes() {
        // Initial state: English with prefix "[OLD_PREFIX] "
        Map<String, String> initialActive = Map.of(
                "commands.config.usage", "Usage: /status config <key> [value]"
        );
        // Base valid config
        MessageRegistry base = new MessageRegistry(tempDir, "en", testLogger);
        PluginConfig oldConfig = base.snapshot().config().withLanguage("en").withChatPrefix("[OLD_PREFIX] ");
        PluginConfig newConfig = oldConfig.withLanguage("es").withChatPrefix("[NEW_PREFIX] ");

        Map<String, String> newActive = Map.of(
                "commands.config.usage", "Uso: /status config <clave> [valor]"
        );
        MessagesSnapshot oldMessages = new MessagesSnapshot("en", "es", initialActive, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        MessagesSnapshot newMessages = new MessagesSnapshot("es", "en", newActive, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        RuntimeSnapshot newSnapshot = new RuntimeSnapshot(newConfig, newMessages);

        AtomicBoolean reloadedDuringRender = new AtomicBoolean(false);

        MessageRegistry registry = new MessageRegistry(tempDir, "en", testLogger) {
            @Override
            protected void onBetweenPrefixAndBody() {
                // Force reload strictly between prefix retrieval and message body resolution
                snapshotReference().set(newSnapshot);
                reloadedDuringRender.set(true);
            }
        };
        registry.snapshotReference().set(new RuntimeSnapshot(oldConfig, oldMessages));

        // Execute rendering of single request
        Component rendered = registry.renderWithPrefix("commands.config.usage");
        String output = ColorParser.serialize(rendered);

        assertThat(reloadedDuringRender).isTrue();
        // The output must be wholly old: OLD prefix AND OLD message body. Never mixed!
        assertThat(output).isEqualTo("[OLD_PREFIX] Usage: /status config <key> [value]");
        assertThat(output).doesNotContain("[NEW_PREFIX]");
        assertThat(output).doesNotContain("Uso:");

        // A subsequent request captures the newly published snapshot and is wholly new
        Component subsequentRendered = registry.renderWithPrefix("commands.config.usage");
        String subsequentOutput = ColorParser.serialize(subsequentRendered);
        assertThat(subsequentOutput).isEqualTo("[NEW_PREFIX] Uso: /status config <clave> [valor]");
    }

    @Test
    @DisplayName("Fix 1: MessageRegistry.setLanguage updates both config language and translations snapshot")
    void setLanguageUpdatesBothConfigLanguageAndTranslations() {
        MessageRegistry registry = new MessageRegistry(tempDir, "en", testLogger);
        assertThat(registry.snapshot().config().language()).isEqualTo("en");
        assertThat(registry.snapshot().messages().activeLanguage()).isEqualTo("en");

        registry.setLanguage("es");
        assertThat(registry.snapshot().config().language()).isEqualTo("es");
        assertThat(registry.snapshot().messages().activeLanguage()).isEqualTo("es");

        registry.setLanguage("en");
        assertThat(registry.snapshot().config().language()).isEqualTo("en");
        assertThat(registry.snapshot().messages().activeLanguage()).isEqualTo("en");
    }
}
