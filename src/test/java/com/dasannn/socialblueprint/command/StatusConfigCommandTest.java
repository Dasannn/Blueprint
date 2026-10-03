package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.MessagesSnapshot;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StatusConfigCommandTest {

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private StatusConfigCommand command;
    private StatusCommandExecutor dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("StatusConfigCommandTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "es", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        command = new StatusConfigCommand(configManager, messageRegistry);
        dispatcher = new StatusCommandExecutor(configManager, messageRegistry);
    }

    private void setLanguage(String lang) {
        configManager.set("language", lang);
        messageRegistry.clear();
    }

    @Test void honorPercentAliasReadsAndEditsCanonicalKey() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");
        command.execute(admin, new String[]{"honor.percent"});
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "honor.cost-percent").containsEntry("value", "8.0");
        command.execute(admin, new String[]{"honor.percent", "12.5"});
        assertThat(configManager.config().honor().costPercent()).isEqualTo(12.5);
    }

    @Test
    void pinnedEnglishRatingWaitText() {
        setLanguage("en");
        var snapshot = configManager.snapshot();
        assertThat(com.dasannn.socialblueprint.feature.honor.HonorService.ratingWaitText(java.time.Duration.ofMinutes(133), snapshot, messageRegistry)).isEqualTo("2 h 13 min");
        assertThat(com.dasannn.socialblueprint.feature.honor.HonorService.ratingWaitText(java.time.Duration.ofSeconds(45), snapshot, messageRegistry)).isEqualTo("45 s");
        assertThat(com.dasannn.socialblueprint.feature.honor.HonorService.ratingWaitText(java.time.Duration.ofMillis(1), snapshot, messageRegistry)).isEqualTo("1 s");
        assertThat(messageRegistry.getRaw(snapshot, "rating-wait.allowed")).isEqualTo("&aYou can rate");
        assertThat(messageRegistry.getRaw(snapshot, "honor.rating-id")).isEqualTo("&7Revoke: /status admin revoke {id}");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-success")).isEqualTo("&aRevoked rating {id} from {rater} to {player}.");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-not-found")).isEqualTo("&cRating {id} not found.");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-not-revocable")).isEqualTo("&cRating {id} is not revocable (already revoked or unsupported kind).");
    }

    @Test
    void pinnedSpanishRatingWaitText() {
        setLanguage("es");
        var snapshot = configManager.snapshot();
        assertThat(com.dasannn.socialblueprint.feature.honor.HonorService.ratingWaitText(java.time.Duration.ofMinutes(133), snapshot, messageRegistry)).isEqualTo("2 h 13 min");
        assertThat(com.dasannn.socialblueprint.feature.honor.HonorService.ratingWaitText(java.time.Duration.ofSeconds(105), snapshot, messageRegistry)).isEqualTo("1 min 45 s");
        assertThat(messageRegistry.getRaw(snapshot, "rating-wait.allowed")).isEqualTo("&aPuedes valorar");
        assertThat(messageRegistry.getRaw(snapshot, "rating-wait.blocked")).isEqualTo("&ePodrás volver a valorar en {time}");
        assertThat(messageRegistry.getRaw(snapshot, "honor.rating-id")).isEqualTo("&7Revocar: /status admin revoke {id}");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-success")).isEqualTo("&aReseña {id} de {rater} a {player} revocada.");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-not-found")).isEqualTo("&cLa reseña {id} no existe.");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoke-not-revocable")).isEqualTo("&cLa reseña {id} no se puede revocar (ya revocada o tipo no revocable).");
        assertThat(messageRegistry.getRaw(snapshot, "honor.revoked")).isEqualTo("&cRevocada por {admin}");
    }

    @Test
    void effectsDebugCanBeEnabledAndDisabledThroughStatusConfig() {
        var admin = new MockSender("Admin", "socialblueprint.admin.config");
        command.execute(admin, new String[]{"effects.debug", "true"});
        assertThat(configManager.config().effects().debug()).isTrue();
        assertThat(configManager.get("effects.debug")).isEqualTo("true");
        command.execute(admin, new String[]{"effects.debug", "false"});
        assertThat(configManager.config().effects().debug()).isFalse();
        command.execute(admin, new String[]{"effects.debug", "maybe"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
        assertThat(configManager.config().effects().debug()).isFalse();
    }

    @Test
    void uniqueSuffixesUseCanonicalValidationPersistenceAndAudit() {
        try (var engine = com.dasannn.socialblueprint.storage.StorageEngine.inMemory()) {
            engine.runMigrations();
            var audits = new com.dasannn.socialblueprint.storage.AuditRepository(engine);
            var cmd = new StatusConfigCommand(configManager, messageRegistry, audits);
            var admin = new MockSender("Admin", "socialblueprint.admin.config");
            cmd.execute(admin, new String[]{"peaceful.cap", "7"});
            assertThat(configManager.get("psychosis.inputs.peaceful.cap")).isEqualTo("7");
            assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "psychosis.inputs.peaceful.cap");
            assertThat(audits.findByTarget(new com.dasannn.socialblueprint.domain.NonPlayerTarget("psychosis.inputs.peaceful.cap")))
                    .hasSize(1);
            cmd.execute(admin, new String[]{"honor-review.gain", "3"});
            assertThat(configManager.get("psychosis.inputs.honor-review.gain")).isEqualTo("3");
            cmd.execute(admin, new String[]{"honor-review.gain", "NaN"});
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
            assertThat(configManager.get("psychosis.inputs.honor-review.gain")).isEqualTo("3");
            cmd.execute(admin, new String[]{"low.interval-ticks"});
            assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "effects.episodes.low.interval-ticks");
            configManager.reload();
            assertThat(configManager.get("psychosis.inputs.peaceful.cap")).isEqualTo("7");
        }
    }

    @Test
    void suffixAmbiguityListsAtMostEightCanonicalKeysAndDoesNotGuess() {
        var admin = new MockSender("Admin", "socialblueprint.admin.config");
        String before = configManager.get("psychosis.inputs.peaceful.cap");
        command.execute(admin, new String[]{"cap", "0"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("config-suffix.ambiguous");
        String keys = messageRegistry.lastCall().placeholders().get("keys");
        assertThat(keys.split(", ")).hasSizeLessThanOrEqualTo(8);
        assertThat(keys).contains("psychosis.inputs.peaceful.cap");
        assertThat(configManager.get("psychosis.inputs.peaceful.cap")).isEqualTo(before);
        command.execute(admin, new String[]{"eaceful.cap"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.invalid-key");
        var completion = command.tabComplete(admin, new String[]{""});
        assertThat(completion).contains("peaceful.cap", "honor-review.gain", "low.interval-ticks", "language");
        assertThat(completion.indexOf("peaceful.cap")).isLessThan(completion.indexOf("psychosis.inputs.peaceful.cap"));
        assertThat(command.tabComplete(admin, new String[]{"set", "peaceful."})).contains("peaceful.cap");
    }

    @Test
    void effectConfigCompletionRetiresOldKeysAndOffersEpisodeControls() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");
        assertThat(command.tabComplete(admin, new String[]{"effects."}))
                .contains("effects.check-interval", "effects.quiet-interval.medium", "effects.quiet-interval.high",
                        "effects.quiet-interval.extreme", "effects.max-episode-ticks")
                .doesNotContain("effects.threshold", "effects.fake-announcement.fake-names");
        assertThat(command.tabComplete(admin, new String[]{"permissions."})).doesNotContain("permissions.effects");
        assertThat(configManager.isEditableKey("permissions.effects")).isFalse();
    }

    @Test
    @DisplayName("T-035: Permission denied when sender lacks admin-config permission")
    void permissionDenied() {
        MockSender sender = new MockSender("RegularPlayer");
        // Sender has no permissions

        boolean result = command.execute(sender, new String[]{"honor.cost"});
        assertThat(result).isTrue();

        assertThat(sender.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
    }

    @Test
    @DisplayName("T-035: /status config with no args displays usage")
    void noArgsDisplaysUsage() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.usage");
    }

    @Test
    @DisplayName("T-035: /status config <key> reads current value")
    void readKeyDisplaysValue() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"honor.cost"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "honor.cost")
                .containsEntry("value", "30.0");
    }

    @Test
    @DisplayName("T-035: /status config <key> with unknown key displays invalid-key message")
    void readUnknownKey() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"nonexistent.key"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.invalid-key");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "nonexistent.key");
    }

    @Test
    @DisplayName("T-035: /status config <key> <value> updates config and reports success")
    void setValidKeyReportsSuccess() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"honor.cost", "650.0"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-success");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "honor.cost")
                .containsEntry("value", "650.0");

        assertThat(configManager.config().honor().cost()).isEqualTo(650.0);
    }

    @Test
    @DisplayName("T-035: /status config <key> <invalid-value> refuses edit and displays error naming key")
    void setInvalidKeyRefusesAndReportsError() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"tiers.tier-1.threshold", "100"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "tiers.tier-1.threshold")
                .containsKey("error");

        // Verify snapshot was untouched
        assertThat(configManager.config().tiers().get(com.dasannn.socialblueprint.domain.Tier.TEMERARIO).threshold()).isEqualTo(-5);
    }

    @Test
    @DisplayName("T-035: /status config reload reloads configuration and reports success")
    void reloadCommandSucceeds() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"reload"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.reload-success");
    }

    @Test
    @DisplayName("T-035: /status config through dispatcher routes correctly")
    void dispatcherRoutesConfigCommand() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = dispatcher.onCommand(admin, null, "status", new String[]{"config", "honor.cost"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "honor.cost")
                .containsEntry("value", "30.0");
    }

    @Test
    @DisplayName("T-035: Tab completion provides suggestions for config keys")
    void tabCompletionProvidesSuggestions() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        List<String> suggestions = command.tabComplete(admin, new String[]{"hon"});
        assertThat(suggestions).contains("honor.cost", "honor.cost-percent", "honor.cap-window", "honor.cooldown-per-pair", "honor.max-per-target");

        List<String> langSuggestions = command.tabComplete(admin, new String[]{"language", ""});
        assertThat(langSuggestions).containsExactly("en", "es");
    }

    @Test
    @DisplayName("T-035: Rendered output in English when language is explicitly pinned to English")
    void renderedOutputInEnglish() {
        setLanguage("en");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"honor.cost", "650.0"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        String msg = admin.sentMessages().getFirst();
        assertThat(msg).isEqualTo("&8[&bSocialBlueprint&8]&r &aSuccessfully updated &bhonor.cost&a to: &f650.0");
    }

    @Test
    @DisplayName("T-035: Rendered output in Spanish when language is explicitly pinned to Spanish")
    void renderedOutputInSpanish() {
        setLanguage("es");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"honor.cost", "650.0"});
        assertThat(result).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        String msg = admin.sentMessages().getFirst();
        assertThat(msg).isEqualTo("&8[&bSocialBlueprint&8]&r &aSe actualiz\u00f3 exitosamente &bhonor.cost&a a: &f650.0");
    }

    @Test
    @DisplayName("Finding 1: Editing chat-prefix in-game immediately changes the rendered prefix seen by players")
    void chatPrefixEditVisiblyChangesRenderedPrefixImmediately() {
        setLanguage("es");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // Before edit: prefix is default &8[&bSocialBlueprint&8]&r
        Component before = messageRegistry.renderWithPrefix("commands.config.usage");
        assertThat(ColorParser.serialize(before)).startsWith("&8[&bSocialBlueprint&8]&r ");

        // In-game command: /status config chat-prefix &4[PROD]&r
        boolean result = command.execute(admin, new String[]{"chat-prefix", "&4[PROD]&r "});
        assertThat(result).isTrue();

        // 1. Snapshot value updated
        assertThat(configManager.config().chatPrefix()).isEqualTo("&4[PROD]&r ");

        // 2. Visible effect in sent response: the set-success notification itself renders with the NEW prefix
        String response = admin.sentMessages().getLast();
        assertThat(response).startsWith("&4[PROD]&r ");

        // 3. Subsequent player-visible messages immediately use the new prefix without restart
        Component after = messageRegistry.renderWithPrefix("commands.config.usage");
        assertThat(ColorParser.serialize(after)).startsWith("&4[PROD]&r ");
    }

    @Test
    @DisplayName("Finding 2: In-game editing of player-visible message immediately changes the message seen by players")
    void messageEditVisiblyChangesRenderedMessageImmediately() {
        setLanguage("es");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // Execute edit: /status config commands.config.usage &eUso personalizado: &f/status config <k> <v>
        boolean result = command.execute(admin, new String[]{
                "commands.config.usage",
                "&eUso personalizado: &f/status config <k> <v>"
        });
        assertThat(result).isTrue();

        // Run /status config with no args to trigger the usage message
        admin.sentMessages().clear();
        boolean usageResult = command.execute(admin, new String[]{});
        assertThat(usageResult).isTrue();

        // Visible effect: the message displayed to the sender is the newly edited string
        assertThat(admin.sentMessages()).hasSize(1);
        String displayed = admin.sentMessages().getFirst();
        assertThat(displayed).isEqualTo("&8[&bSocialBlueprint&8]&r &eUso personalizado: &f/status config <k> <v>");
    }

    @Test
    @DisplayName("Finding 3: Setting an unknown or uneditable key is rejected with invalid-key before mutating anything")
    void unknownKeyIsRejectedWithInvalidKeyMessage() {
        setLanguage("es");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // Unknown key: imaginary.key
        boolean result1 = command.execute(admin, new String[]{"imaginary.key", "someValue"});
        assertThat(result1).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.invalid-key");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "imaginary.key");

        // Typos in valid sections: tiers.tier0.typo
        messageRegistry.clear();
        boolean result2 = command.execute(admin, new String[]{"tiers.tier0.typo", "someValue"});
        assertThat(result2).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.invalid-key");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "tiers.tier0.typo");
    }

    @Test
    @DisplayName("Fix 2: Setting legacy prefix key is rejected with invalid-key before mutation")
    void legacyPrefixKeyIsRejectedWithInvalidKey() {
        setLanguage("es");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        boolean result = command.execute(admin, new String[]{"prefix", "&4[TEST]&r "});
        assertThat(result).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("config-suffix.ambiguous");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "prefix");
    }

    @Test
    @DisplayName("Fix 1: Command request capturing snapshot once never mixes output when reload intervenes during execution")
    void commandExecutionNeverMixesWhenReloadIntervenes() {
        setLanguage("en");
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // Prepare new snapshot for reload: Spanish + new prefix
        Map<String, String> esMessages = Map.of(
                "commands.config.usage", "Uso: /status config <clave> [valor]"
        );
        PluginConfig esConfig = configManager.config()
                .withLanguage("es")
                .withChatPrefix("&4[NEW]&r ");
        MessagesSnapshot esMs = new MessagesSnapshot("es", "en", esMessages, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        RuntimeSnapshot esSnapshot = new RuntimeSnapshot(esConfig, esMs);

        AtomicReference<RuntimeSnapshot> sharedRef = new AtomicReference<>();
        AtomicBoolean interleaved = new AtomicBoolean(false);

        // Subclass MessageRegistry to force a reload strictly between prefix and body rendering
        MessageRegistry interleavingRegistry = new MessageRegistry(tempDir, "en", Logger.getLogger("test")) {
            @Override
            public AtomicReference<RuntimeSnapshot> snapshotReference() {
                return sharedRef;
            }

            @Override
            public RuntimeSnapshot snapshot() {
                return sharedRef.get();
            }

            @Override
            protected void onBetweenPrefixAndBody() {
                sharedRef.set(esSnapshot);
                interleaved.set(true);
            }
        };
        ConfigManager testConfigManager = new ConfigManager(new File(tempDir, "config.yml"), interleavingRegistry, Runnable::run, Logger.getLogger("test"));
        testConfigManager.initialize();
        sharedRef.set(testConfigManager.snapshot());

        StatusConfigCommand testCommand = new StatusConfigCommand(testConfigManager, interleavingRegistry);

        boolean result = testCommand.execute(admin, new String[]{});
        assertThat(result).isTrue();
        assertThat(interleaved).isTrue();

        assertThat(admin.sentMessages()).hasSize(1);
        String sent = admin.sentMessages().getFirst();

        // The sent component must be wholly old (English usage with original chat prefix)
        // It must NEVER be a mix of old prefix with Spanish text or new prefix with English text
        assertThat(sent).startsWith("&8[&bSocialBlueprint&8]&r ");
        assertThat(sent).contains("Usage:");
        assertThat(sent).doesNotContain("&4[NEW]");
        assertThat(sent).doesNotContain("Uso:");
    }

    @Test
    @DisplayName("Finding 2: /status config reload runs off the command thread and replies on the main thread")
    void finding2_reloadSubmitsThroughIoExecutorAndRepliesOnMainThread() throws Exception {
        AtomicReference<Thread> reloadThread = new AtomicReference<>();
        AtomicReference<Thread> replyThread = new AtomicReference<>();
        java.util.concurrent.ExecutorService testIo = java.util.concurrent.Executors.newSingleThreadExecutor();

        File configFile = new File(tempDir, "config-finding2.yml");
        Files.copy(new File(tempDir, "config.yml").toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        ConfigManager customConfig = new ConfigManager(
                configFile,
                messageRegistry,
                r -> testIo.submit(() -> {
                    reloadThread.set(Thread.currentThread());
                    r.run();
                }),
                () -> "1.0",
                Logger.getLogger("test")
        );
        customConfig.initialize();

        java.util.concurrent.CountDownLatch replied = new java.util.concurrent.CountDownLatch(1);
        StatusConfigCommand customCmd = new StatusConfigCommand(
                customConfig,
                messageRegistry,
                null,
                r -> {
                    replyThread.set(Thread.currentThread());
                    r.run();
                    replied.countDown();
                },
                Logger.getLogger("test")
        );

        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");
        boolean executed = customCmd.execute(admin, new String[]{"reload"});
        assertThat(executed).isTrue();

        // The reply runner trips the latch, so the wait ends when the work ends
        // rather than when a poll interval happens to notice.
        assertThat(replied.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(reloadThread.get()).isNotNull();
        assertThat(reloadThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(replyThread.get()).isNotNull();

        testIo.shutdownNow();
    }

    @Test
    @DisplayName("Finding 8: /status config <key> [value] edits disabled-worlds in-game")
    void finding8_editDisabledWorldsInGame() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // 1. Read default value
        boolean readResult = command.execute(admin, new String[]{"disabled-worlds"});
        assertThat(readResult).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "disabled-worlds")
                .containsEntry("value", "[minigames]");

        // 2. Set bracketed list: [nether, the_end]
        messageRegistry.clear();
        boolean setResult = command.execute(admin, new String[]{"disabled-worlds", "[nether, the_end]"});
        assertThat(setResult).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-success");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("key", "disabled-worlds")
                .containsEntry("value", "[nether, the_end]");

        assertThat(configManager.config().worldRules().disabledWorlds()).containsExactlyInAnyOrder("nether", "the_end");

        // 3. Read back
        messageRegistry.clear();
        command.execute(admin, new String[]{"disabled-worlds"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders().get("value")).contains("nether").contains("the_end");
    }

    @Test
    @DisplayName("Finding 7: /status config sounds.<slot>.<property> validates volume and pitch in-game")
    void finding7_inGameSoundValidation() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        // 1. Invalid volume: negative
        boolean setNegativeVol = command.execute(admin, new String[]{"sounds.creeper-fuse.volume", "-0.5"});
        assertThat(setNegativeVol).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
        assertThat(messageRegistry.lastCall().placeholders().get("error")).contains("volume").contains("creeper-fuse");

        // 2. Invalid pitch: > 2.0
        messageRegistry.clear();
        boolean setHighPitch = command.execute(admin, new String[]{"sounds.creeper-fuse.pitch", "2.5"});
        assertThat(setHighPitch).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
        assertThat(messageRegistry.lastCall().placeholders().get("error")).contains("pitch").contains("creeper-fuse");

        // 3. Invalid pitch: < 0.0
        messageRegistry.clear();
        boolean setLowPitch = command.execute(admin, new String[]{"sounds.creeper-fuse.pitch", "-0.1"});
        assertThat(setLowPitch).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
        assertThat(messageRegistry.lastCall().placeholders().get("error")).contains("pitch").contains("creeper-fuse");

        // 4. Valid pitch: 1.5
        messageRegistry.clear();
        boolean setValidPitch = command.execute(admin, new String[]{"sounds.creeper-fuse.pitch", "1.5"});
        assertThat(setValidPitch).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-success");
        assertThat(configManager.config().sounds().creeperFuse().pitch()).isEqualTo(1.5f);
    }

    @Test
    @DisplayName("Finding 8: /status config set derives the actor on the calling thread")
    void finding8_setCapturesActorBeforeAsyncExecution() throws Exception {
        UUID expectedUuid = UUID.randomUUID();
        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            AuditRepository auditRepo = new AuditRepository(storage);

            StatusConfigCommand customCmd = new StatusConfigCommand(
                    configManager,
                    messageRegistry,
                    auditRepo,
                    Runnable::run,
                    Logger.getLogger("test")
            );

            // The proxy is the assertion: if the actor were derived inside the
            // asynchronous write instead of before it, getUniqueId would run off
            // this thread and throw, and no audit row would carry the right uuid.
            Thread mainThread = Thread.currentThread();
            InvocationHandler playerHandler = (proxy, method, args) -> {
                if ("getUniqueId".equals(method.getName())) {
                    if (Thread.currentThread() != mainThread) {
                        throw new IllegalStateException("Player.getUniqueId() called off main thread!");
                    }
                    return expectedUuid;
                }
                if ("getName".equals(method.getName())) return "AdminPlayer";
                if ("hasPermission".equals(method.getName())) return true;
                return null;
            };
            Player player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    playerHandler
            );

            boolean executed = customCmd.execute(player, new String[]{"honor.cost", "150"});
            assertThat(executed).isTrue();

            List<AuditEvent> audits = auditRepo.findByTarget(NonPlayerTarget.configKey("honor.cost"));
            assertThat(audits).isNotEmpty();
            assertThat(audits.getFirst().actor()).isEqualTo(PlayerId.of(expectedUuid));
        }
    }

    @Test
    @DisplayName("Finding 3: /status config get serves from snapshot without touching disk")
    void finding3_getServesFromSnapshotWithoutReadingFile() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        // Delete the config file from disk completely to guarantee any disk read would fail
        File deletedConfig = new File(tempDir, "config-deleted.yml");
        Files.move(configFile.toPath(), deletedConfig.toPath(), StandardCopyOption.REPLACE_EXISTING);

        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");
        messageRegistry.clear();

        // Reading honor.cost must serve from the in-memory snapshot leaf values
        boolean result = command.execute(admin, new String[]{"honor.cost"});
        assertThat(result).isTrue();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders().get("key")).isEqualTo("honor.cost");
        // 30.0 is the shipped default; the point of the test is that it was read
        // with config.yml renamed out from under the command.
        assertThat(messageRegistry.lastCall().placeholders().get("value")).isEqualTo("30.0");

        // Restore file
        Files.move(deletedConfig.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }


    @Test
    void featurePermissionUsesSharedPersistenceReloadAndAuditWithoutConfigPermission() {
        String key = "psychosis.inputs.kill.enabled";
        MockSender features = new MockSender("Features", "socialblueprint.admin.features");
        try (var storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            var audits = new AuditRepository(storage);
            var edits = new StatusConfigCommand(configManager, messageRegistry, audits);
            var before = configManager.snapshot();
            edits.toggleFeatureAsync(features, key, before).join();
            assertThat(configManager.snapshot()).isNotSameAs(before);
            assertThat(configManager.config().psychosis().input(com.dasannn.socialblueprint.domain.MindInput.KILL).enabled()).isFalse();
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-success");
            var audit = audits.findByTarget(NonPlayerTarget.configKey(key));
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst().operation()).isEqualTo("config_set");
            assertThat(audit.getFirst().before()).isEqualTo("true");
            assertThat(audit.getFirst().after()).isEqualTo("false");
            var restarted = new ConfigManager(new File(tempDir, "config.yml"), messageRegistry, Runnable::run, null);
            restarted.initialize();
            assertThat(restarted.get(key)).isEqualTo("false");
            edits.toggleFeatureAsync(features, key, configManager.snapshot()).join();
            assertThat(configManager.get(key)).isEqualTo("true");
            assertThat(audits.findByTarget(NonPlayerTarget.configKey(key))).hasSize(2);
            edits.executeAsync(features, new String[]{"honor.cost", "1"}, configManager.snapshot()).join();
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
        }
    }

    @Test
    void featureTogglesRejectOtherLeavesAndConfigOnlyPermission() {
        MockSender features = new MockSender("Features", "socialblueprint.admin.features");
        var before = configManager.snapshot();
        for (String key : List.of("honor.cost", "psychosis.inputs.kill.serene-drain")) {
            command.toggleFeatureAsync(features, key, before).join();
            assertThat(messageRegistry.lastCall().key()).isEqualTo("features.unavailable");
            assertThat(configManager.snapshot()).isSameAs(before);
        }
        command.toggleFeatureAsync(new MockSender("Config", "socialblueprint.admin.config"),
                "psychosis.chat.enabled", before).join();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
        assertThat(configManager.snapshot()).isSameAs(before);
    }

    @Test
    void featureConsoleUsageAndCompletionArePermissionAware() {
        MockSender features = new MockSender("Console", "socialblueprint.admin.features");
        assertThat(dispatcher.onTabComplete(features, null, "status", new String[]{"a"})).contains("admin");
        assertThat(dispatcher.onTabComplete(features, null, "status", new String[]{"admin", "f"})).containsExactly("features");
        assertThat(dispatcher.onTabComplete(features, null, "status", new String[]{"admin", "features", ""})).isEmpty();
        assertThat(dispatcher.onTabComplete(new MockSender("Regular"), null, "status", new String[]{"admin", "f"})).isEmpty();
        dispatcher.onCommand(features, null, "status", new String[]{"admin", "features"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("features.usage");
        dispatcher.onCommand(new MockSender("Regular"), null, "status", new String[]{"admin", "features"});
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
        configManager.set("permissions.admin-features", "server.features");
        assertThat(dispatcher.onTabComplete(new MockSender("Custom", "server.features"), null, "status", new String[]{"admin", "f"})).containsExactly("features");
    }

    private static class MockSender implements CommandSender {
        private final String name;
        private final Set<String> permissions = new HashSet<>();
        private final List<String> sentMessages = new ArrayList<>();

        public MockSender(String name, String... permissions) {
            this.name = name;
            for (String perm : permissions) {
                this.permissions.add(perm);
            }
        }

        public List<String> sentMessages() {
            return sentMessages;
        }

        @Override
        public void sendMessage(String message) {
            sentMessages.add(message);
        }

        @Override
        public void sendMessage(String... messages) {
            sentMessages.addAll(List.of(messages));
        }

        @Override
        public void sendMessage(UUID sender, String message) {
            sentMessages.add(message);
        }

        @Override
        public void sendMessage(UUID sender, String... messages) {
            sentMessages.addAll(List.of(messages));
        }

        @Override
        public void sendMessage(Component component) {
            sentMessages.add(ColorParser.serialize(component));
        }

        @Override
        public boolean hasPermission(String name) {
            return permissions.contains(name);
        }

        @Override
        public boolean hasPermission(Permission perm) {
            return permissions.contains(perm.getName());
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Server getServer() {
            return null;
        }

        @Override
        public boolean isOp() {
            return false;
        }

        @Override
        public void setOp(boolean value) {
        }

        @Override
        public boolean isPermissionSet(String name) {
            return permissions.contains(name);
        }

        @Override
        public boolean isPermissionSet(Permission perm) {
            return permissions.contains(perm.getName());
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
            return null;
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
        }

        @Override
        public void recalculatePermissions() {
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return Set.of();
        }

        @Override
        public Spigot spigot() {
            return null;
        }

        @Override
        public Component name() {
            return Component.text(name);
        }
    }

    private static class RecordingMessageRegistry extends MessageRegistry {
        record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

        private final List<RenderCall> renderedCalls = new ArrayList<>();

        RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Collections.emptyMap());
        }

        @Override
        public Component renderWithPrefix(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(key, placeholders);
        }

        @Override
        public Component renderWithPrefix(String key) {
            renderedCalls.add(new RenderCall(key, Map.of(), true));
            return super.renderWithPrefix(key);
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(snapshot, key, placeholders);
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key) {
            return render(snapshot, key, Collections.emptyMap());
        }

        @Override
        public Component render(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(key, placeholders);
        }

        @Override
        public Component render(String key) {
            renderedCalls.add(new RenderCall(key, Map.of(), false));
            return super.render(key);
        }

        public List<RenderCall> renderedCalls() {
            return Collections.unmodifiableList(renderedCalls);
        }

        public RenderCall lastCall() {
            if (renderedCalls.isEmpty()) {
                throw new IllegalStateException("No render calls recorded");
            }
            return renderedCalls.getLast();
        }

        public void clear() {
            renderedCalls.clear();
        }
    }
}
