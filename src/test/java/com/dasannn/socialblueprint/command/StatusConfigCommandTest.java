package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.MessagesSnapshot;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                .containsEntry("value", "500.0");
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
        assertThat(configManager.config().tiers().get(com.dasannn.socialblueprint.domain.Tier.TEMERARIO).threshold()).isEqualTo(-1);
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
                .containsEntry("value", "500.0");
    }

    @Test
    @DisplayName("T-035: Tab completion provides suggestions for config keys")
    void tabCompletionProvidesSuggestions() {
        MockSender admin = new MockSender("Admin", "socialblueprint.admin.config");

        List<String> suggestions = command.tabComplete(admin, new String[]{"hon"});
        assertThat(suggestions).contains("honor.cost", "honor.window", "honor.cooldown-per-pair", "honor.max-per-target");

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
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.invalid-key");
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
