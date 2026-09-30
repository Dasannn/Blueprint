package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class StatusHistoryCommandTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private ProfileService profileService;
    private StatusHistoryCommand historyCommand;

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new HashMap<>();
    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("StatusHistoryCommandTest-" + System.nanoTime());
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

        historyCommand = new StatusHistoryCommand(
                profileService,
                reputationRepo,
                messageRegistry,
                Runnable::run
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private static class MockSenderRecord {
        final CommandSender sender;
        final List<String> receivedMessages = new ArrayList<>();

        MockSenderRecord(String name, boolean isOp, String... permissions) {
            Set<String> perms = new HashSet<>(List.of(permissions));
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

            InvocationHandler handler = (proxy, method, args) -> {
                String mName = method.getName();
                if ("getName".equals(mName)) return name;
                if ("isOp".equals(mName)) return isOp;
                if ("hasPermission".equals(mName) && args != null && args.length > 0) {
                    String p = String.valueOf(args[0]);
                    return isOp || perms.contains(p) || perms.contains("socialblueprint.*");
                }
                if ("sendMessage".equals(mName) && args != null && args.length > 0) {
                    if (args[0] instanceof Component comp) {
                        receivedMessages.add(PlainTextComponentSerializer.plainText().serialize(comp));
                    } else if (args[0] != null) {
                        receivedMessages.add(args[0].toString());
                    }
                    return null;
                }
                if ("getUniqueId".equals(mName)) return uuid;
                return defaultValue(method.getReturnType());
            };

            this.sender = (CommandSender) Proxy.newProxyInstance(
                    CommandSender.class.getClassLoader(),
                    new Class<?>[]{CommandSender.class},
                    handler
            );
        }
    }

    private Player registerPlayer(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        PlayerId pid = PlayerId.of(uuid);
        onlineLookupMap.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(pid, name, true));
        onlineLookupMap.put(uuid.toString(), new PlayerLookup.KnownPlayer(pid, name, true));

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            return defaultValue(method.getReturnType());
        };

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );
    }

    @Test
    @DisplayName("T-134: SYSTEM_KILL event renders in /status history with translated reason and System actor")
    void t134_rendersSystemKillWithTranslatedReasonAndSystemActor() {
        Player target = registerPlayer("TargetPlayer");
        PlayerId targetId = PlayerId.of(target.getUniqueId());

        // Save a SYSTEM_KILL event for target
        reputationRepo.save(new ReputationEvent(
                0L,
                null, // System actor
                targetId,
                -1,
                HonorKind.SYSTEM_KILL,
                0.0,
                "kill-penalty.reason",
                baseTime
        ));

        MockSenderRecord senderRecord = new MockSenderRecord("Viewer", false, "socialblueprint.show.others", "socialblueprint.view");
        RuntimeSnapshot snapshot = configManager.snapshot();

        historyCommand.execute(senderRecord.sender, new String[]{"TargetPlayer"}, snapshot).join();

        assertThat(senderRecord.receivedMessages).isNotEmpty();
        // Header
        assertThat(senderRecord.receivedMessages.getFirst()).contains("TargetPlayer");

        // Entry contains System actor, -1 delta, and translated reason
        String entry = senderRecord.receivedMessages.stream()
                .filter(m -> m.contains("-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry containing -1 found in: " + senderRecord.receivedMessages));

        assertThat(entry).contains("System");
        assertThat(entry).contains("-1");
        // Translated reason: "Open-world kill penalty" from messages_en.yml
        assertThat(entry).contains("Open-world kill penalty");
        // Must NOT render raw message key
        assertThat(entry).doesNotContain("kill-penalty.reason");
    }

    @Test
    @DisplayName("T-134: History renders in Spanish when language is set to 'es'")
    void t134_rendersSystemKillInSpanish() {
        Player target = registerPlayer("SpanishTarget");
        PlayerId targetId = PlayerId.of(target.getUniqueId());

        reputationRepo.save(new ReputationEvent(
                0L,
                null,
                targetId,
                -1,
                HonorKind.SYSTEM_KILL,
                0.0,
                "kill-penalty.reason",
                baseTime
        ));

        configManager.set("language", "es");
        RuntimeSnapshot snapshot = configManager.snapshot();

        MockSenderRecord senderRecord = new MockSenderRecord("Viewer", false, "socialblueprint.show.others", "socialblueprint.view");
        historyCommand.execute(senderRecord.sender, new String[]{"SpanishTarget"}, snapshot).join();

        assertThat(senderRecord.receivedMessages).isNotEmpty();
        String entry = senderRecord.receivedMessages.stream()
                .filter(m -> m.contains("-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry containing -1 found in: " + senderRecord.receivedMessages));

        // System actor in Spanish: "Sistema"
        assertThat(entry).contains("Sistema");
        // Translated reason in Spanish: "Penalización por muerte fuera de duelo"
        assertThat(entry).contains("Penalización por muerte fuera de duelo");
        assertThat(entry).doesNotContain("kill-penalty.reason");
    }

    @Test
    @DisplayName("T-134: Empty history renders empty message")
    void t134_rendersEmptyHistory() {
        registerPlayer("CleanPlayer");

        MockSenderRecord senderRecord = new MockSenderRecord("Viewer", false, "socialblueprint.show.others", "socialblueprint.view");
        RuntimeSnapshot snapshot = configManager.snapshot();

        historyCommand.execute(senderRecord.sender, new String[]{"CleanPlayer"}, snapshot).join();

        assertThat(senderRecord.receivedMessages).isNotEmpty();
        assertThat(senderRecord.receivedMessages.getFirst()).contains("CleanPlayer");
        assertThat(senderRecord.receivedMessages.get(1)).contains("No reputation events recorded");
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
