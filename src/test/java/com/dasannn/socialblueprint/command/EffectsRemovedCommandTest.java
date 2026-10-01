package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import com.dasannn.socialblueprint.domain.HonorKind;

class EffectsRemovedCommandTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private ProfileRepository profileRepo;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileService profileService;
    private StatusCommandExecutor commandExecutor;

    public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

    public static class RecordingMessageRegistry extends MessageRegistry {
        private final List<RenderCall> renderedCalls = new CopyOnWriteArrayList<>();

        public RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        public void clearCalls() {
            renderedCalls.clear();
        }

        public List<RenderCall> renderedCalls() {
            return Collections.unmodifiableList(renderedCalls);
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
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
            return renderWithPrefix(key, Collections.emptyMap());
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
    }

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("EffectsRemovedCommandTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);

        PlayerLookup testLookup = nameOrUuid -> Optional.empty();

        profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                testLookup,
                logger
        );

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profileService,
                null,
                null,
                null,
                Runnable::run,
                Collections::emptyList,
                null
        );
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private interface TestPlayer extends Player {
        List<Component> getSentMessages();
    }

    private TestPlayer mockPlayer(String name, UUID uuid, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        List<Component> messages = new ArrayList<>();

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("equals".equals(mName) && method.getParameterCount() == 1) return proxy == args[0];
            if ("hashCode".equals(mName) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
            if ("toString".equals(mName) && method.getParameterCount() == 0) return name;
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("hasPermission".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof String perm) {
                    return perms.contains(perm) || perms.contains("socialblueprint.*");
                }
                return false;
            }
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(comp);
                }
                return null;
            }
            if ("getSentMessages".equals(mName)) return messages;
            return defaultValue(method.getReturnType());
        };

        return (TestPlayer) Proxy.newProxyInstance(
                TestPlayer.class.getClassLoader(),
                new Class<?>[]{TestPlayer.class},
                handler
        );
    }

    private CommandSender mockConsole() {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("equals".equals(mName) && method.getParameterCount() == 1) return proxy == args[0];
            if ("hashCode".equals(mName) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
            if ("toString".equals(mName) && method.getParameterCount() == 0) return "CONSOLE";
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) return null;
            return defaultValue(method.getReturnType());
        };
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler
        );
    }

    private Command mockCommand() {
        return new Command("status") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == char.class) return '\0';
        return null;
    }

    @Test
    void retiredEffectsCommandIsRefusedForPlayersAndConsoleInBothLanguages() {
        for (String language : List.of("en", "es")) {
            configManager.set("language", language);
            for (CommandSender sender : List.of(mockPlayer("Alice", UUID.randomUUID(), "socialblueprint.*"), mockConsole())) {
                messageRegistry.clearCalls();
                assertThat(commandExecutor.onCommand(sender, mockCommand(), "status", new String[]{"effects"})).isTrue();
                assertThat(messageRegistry.hasCall("commands.unknown-subcommand")).isTrue();
                assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
                    assertThat(call.key()).isEqualTo("commands.unknown-subcommand");
                    assertThat(call.placeholders()).containsEntry("command", "effects");
                });
            }
        }
    }

    @Test
    void tabCompletionNeverOffersEffectsEvenWithWildcardPermission() {
        TestPlayer player = mockPlayer("Alice", UUID.randomUUID(), "socialblueprint.*");
        assertThat(commandExecutor.onTabComplete(player, mockCommand(), "status", new String[]{"eff"})).doesNotContain("effects");
        assertThat(commandExecutor.onTabComplete(player, mockCommand(), "status", new String[]{""})).doesNotContain("effects");
        assertThat(commandExecutor.onTabComplete(mockConsole(), mockCommand(), "status", new String[]{"eff"})).doesNotContain("effects");
    }
}
