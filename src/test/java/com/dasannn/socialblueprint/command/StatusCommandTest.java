package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class StatusCommandTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private ProfileService profileService;
    private StatusCommandExecutor commandExecutor;

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("StatusCommandTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();
        configManager.set("language", "en");

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);

        PlayerLookup testLookup = nameOrUuid -> {
            if (onlineLookupMap.containsKey(nameOrUuid.toLowerCase())) {
                return Optional.of(onlineLookupMap.get(nameOrUuid.toLowerCase()));
            }
            return Optional.empty();
        };

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
                Runnable::run,
                () -> List.of(mockPlayer("OnlineAlice"), mockPlayer("OnlineBob"))
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private Player mockPlayer(String name, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        List<String> messages = new ArrayList<>();
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("hasPermission".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof String perm) {
                    return perms.contains(perm);
                }
                return false;
            }
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(ColorParser.serialize(comp));
                }
                return null;
            }
            if ("sentMessages".equals(mName)) return messages;
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, MessageRecorder.class},
                handler
        );
        onlineLookupMap.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(PlayerId.of(uuid), name, true));
        return player;
    }

    private CommandSender mockConsole(List<String> messages) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true; // Console has all permissions
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(ColorParser.serialize(comp));
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        };

        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler
        );
    }

    private interface MessageRecorder {
        List<String> sentMessages();
    }

    @SuppressWarnings("unchecked")
    private List<String> getMessages(Player player) {
        return ((MessageRecorder) player).sentMessages();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        return null;
    }

    @Test
    @DisplayName("T-045: Player /status with no args shows own profile in English")
    void playerViewOwnProfileInEnglish() {
        Player player = mockPlayer("Steve", "socialblueprint.show");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);

        // Line 1: Header
        assertThat(messages.get(0)).contains("&8--- &bSocial Status: &eSteve &8---");
        // Line 2: Tier
        assertThat(messages.get(1)).contains("&7Tier: &7[&f|&7] &fCitizen");
        // Line 3: Status
        assertThat(messages.get(2)).contains("&7Status Score: &f0");
        // Line 4: Confidence
        assertThat(messages.get(3)).contains("&7Confidence: &fUnknown");
        // Line 5: Psychosis
        assertThat(messages.get(4)).contains("&7Psychosis: &fLow");
        // Line 6: Contributors
        assertThat(messages.get(5)).contains("&7Contributors: &f0");
    }

    @Test
    @DisplayName("T-045: Player /status with no args shows own profile in Spanish when language is es")
    void playerViewOwnProfileInSpanish() {
        configManager.set("language", "es");
        Player player = mockPlayer("Steve", "socialblueprint.show");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);

        // Line 1: Header
        assertThat(messages.get(0)).contains("&8--- &bEstatus Social: &eSteve &8---");
        // Line 2: Tier
        assertThat(messages.get(1)).contains("&7Rango: &7[&f|&7] &fParticular");
        // Line 3: Status
        assertThat(messages.get(2)).contains("&7Puntuaci\u00f3n de Estatus: &f0");
        // Line 4: Confidence
        assertThat(messages.get(3)).contains("&7Confianza: &fDesconocida");
        // Line 5: Psychosis
        assertThat(messages.get(4)).contains("&7Psicosis: &fBaja");
        // Line 6: Contributors
        assertThat(messages.get(5)).contains("&7Colaboradores: &f0");
    }

    @Test
    @DisplayName("T-045: /status without show permission denies access")
    void permissionDeniedSelf() {
        Player player = mockPlayer("Unprivileged"); // No permissions

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).contains("&cYou do not have permission to execute this command.");
    }

    @Test
    @DisplayName("T-045: Console running /status with no args receives player-only error message")
    void consoleNoArgsReceivesPlayerOnly() {
        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        boolean result = commandExecutor.onCommand(console, null, "status", new String[]{});
        assertThat(result).isTrue();

        assertThat(consoleMessages).hasSize(1);
        assertThat(consoleMessages.getFirst()).contains("&cThis command can only be executed by players.");
    }

    @Test
    @DisplayName("T-045: /status <player> works from Console and displays profile")
    void consoleViewPlayerProfile() {
        PlayerId targetId = PlayerId.of(UUID.randomUUID());
        PlayerId rater = PlayerId.of(UUID.randomUUID());

        // Add reputation events for target
        reputationRepo.save(new ReputationEvent(rater, targetId, 15, HonorKind.POSITIVE, 500.0, null, Instant.now().minusSeconds(60)));

        onlineLookupMap.put("alex", new PlayerLookup.KnownPlayer(targetId, "Alex", false));

        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        boolean result = commandExecutor.onCommand(console, null, "status", new String[]{"Alex"});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        assertThat(consoleMessages).hasSize(6);
        assertThat(consoleMessages.get(0)).contains("&8--- &bSocial Status: &eAlex &8---");
        assertThat(consoleMessages.get(1)).contains("&7Tier: &7[&a||&7] &fHonorable");
        assertThat(consoleMessages.get(2)).contains("&7Status Score: &f15");
        assertThat(consoleMessages.get(5)).contains("&7Contributors: &f1");
    }

    @Test
    @DisplayName("T-045: /status <unknown-player> displays not-found message")
    void playerNotFoundDisplaysError() {
        Player player = mockPlayer("Inspector", "socialblueprint.show.others");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{"GhostPlayerXYZ"});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).contains("&cPlayer not found: &fGhostPlayerXYZ");
    }

    @Test
    @DisplayName("T-045 / SB-005: Querying a valid UUID of an unrecorded player returns neutral metrics")
    void validUuidUnrecordedPlayerReturnsNeutral() {
        Player player = mockPlayer("Inspector", "socialblueprint.show.others");
        UUID randomUuid = UUID.randomUUID();

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{randomUuid.toString()});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);
        assertThat(messages.get(2)).contains("&7Status Score: &f0");
        assertThat(messages.get(3)).contains("&7Confidence: &fUnknown");
        assertThat(messages.get(4)).contains("&7Psychosis: &fLow");
        assertThat(messages.get(5)).contains("&7Contributors: &f0");
    }

    @Test
    @DisplayName("T-045: Tab completion suggests 'config' and online player names")
    void tabCompletionSuggestsConfigAndPlayers() {
        Player admin = mockPlayer("Admin", "socialblueprint.admin.config");

        List<String> completions = commandExecutor.onTabComplete(admin, null, "status", new String[]{""});
        assertThat(completions).contains("config", "OnlineAlice", "OnlineBob");

        List<String> filtered = commandExecutor.onTabComplete(admin, null, "status", new String[]{"onlinea"});
        assertThat(filtered).containsExactly("OnlineAlice");
    }
}
