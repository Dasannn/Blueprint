package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.gui.StatusGuiService;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    private RecordingMessageRegistry recordingRegistry;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private ProfileService profileService;
    private RaterRevealRepository raterRevealRepo;
    private StatusGuiService statusGuiService;
    private StatusHistoryCommand historyCommand;

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new HashMap<>();
    private final Map<UUID, String> offlineNames = new HashMap<>();
    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    public record RenderCall(
            String key,
            Map<String, String> stringPlaceholders,
            Map<String, Component> componentPlaceholders
    ) {}

    public static class RecordingMessageRegistry extends MessageRegistry {
        final List<RenderCall> renderCalls = new ArrayList<>();

        RecordingMessageRegistry(File dataFolder, String defaultLocale, Logger logger) {
            super(dataFolder, defaultLocale, logger);
        }

        @Override
        public Component render(
                RuntimeSnapshot snapshot,
                String key,
                Map<String, String> placeholders,
                Map<String, Component> componentPlaceholders
        ) {
            renderCalls.add(new RenderCall(
                    key,
                    placeholders != null ? Map.copyOf(placeholders) : Map.of(),
                    componentPlaceholders != null ? Map.copyOf(componentPlaceholders) : Map.of()
            ));
            return super.render(snapshot, key, placeholders, componentPlaceholders);
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            return render(snapshot, key, placeholders, Map.of());
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key) {
            return render(snapshot, key, Map.of(), Map.of());
        }

        @Override
        public Component render(String key, Map<String, String> placeholders) {
            return render(snapshot(), key, placeholders, Map.of());
        }

        @Override
        public Component render(String key, Map<String, String> placeholders, Map<String, Component> componentPlaceholders) {
            return render(snapshot(), key, placeholders, componentPlaceholders);
        }

        @Override
        public Component render(String key) {
            return render(snapshot(), key, Map.of(), Map.of());
        }

        public List<RenderCall> renderCalls() {
            return Collections.unmodifiableList(renderCalls);
        }

        public List<RenderCall> findCalls(String key) {
            return renderCalls.stream().filter(c -> c.key().equals(key)).toList();
        }

        public Optional<RenderCall> findFirstCall(String key) {
            return renderCalls.stream().filter(c -> c.key().equals(key)).findFirst();
        }

        public Optional<RenderCall> findLastCall(String key) {
            return renderCalls.stream().filter(c -> c.key().equals(key)).reduce((first, second) -> second);
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

        Logger logger = Logger.getLogger("StatusHistoryCommandTest-" + System.nanoTime());
        recordingRegistry = new RecordingMessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, recordingRegistry, Runnable::run, logger);
        configManager.initialize();
        configManager.set("language", "en");

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);
        raterRevealRepo = new RaterRevealRepository(storage);

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

        AuditRepository auditRepo = new AuditRepository(storage);
        CompensationRepository compRepo = new CompensationRepository(storage);
        HonorService honorService = new HonorService(
                configManager,
                recordingRegistry,
                reputationRepo,
                auditRepo,
                compRepo,
                profileService,
                null,
                Runnable::run
        );

        statusGuiService = new StatusGuiService(
                recordingRegistry,
                profileService,
                reputationRepo,
                raterRevealRepo,
                honorService,
                Runnable::run,
                null,
                uuid -> offlineNames.containsKey(uuid) ? createMockOfflinePlayer(uuid, offlineNames.get(uuid)) : null,
                Clock.systemUTC()
        );

        historyCommand = new StatusHistoryCommand(
                profileService,
                reputationRepo,
                recordingRegistry,
                Runnable::run,
                raterRevealRepo,
                statusGuiService
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private OfflinePlayer createMockOfflinePlayer(UUID uuid, String name) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            return defaultValue(method.getReturnType());
        };
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                handler
        );
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

    private static class MockPlayerRecord {
        final Player player;
        final List<String> receivedMessages = new ArrayList<>();

        MockPlayerRecord(String name, boolean isOp, String... permissions) {
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

            this.player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    handler
            );
        }
    }

    private Player registerPlayer(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        PlayerId pid = PlayerId.of(uuid);
        onlineLookupMap.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(pid, name, true));
        onlineLookupMap.put(uuid.toString(), new PlayerLookup.KnownPlayer(pid, name, true));
        offlineNames.put(uuid, name);

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

        // Assert on message keys and substitutions, never rendered text
        RenderCall entryCall = recordingRegistry.findFirstCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        assertThat(entryCall.key()).isEqualTo("status.history-entry");
        assertThat(entryCall.stringPlaceholders().get("actor")).isEqualTo("System");
        assertThat(entryCall.stringPlaceholders().get("reason")).isEqualTo("Open-world kill penalty");
        assertThat(entryCall.componentPlaceholders()).containsKey("delta");
        assertThat(entryCall.stringPlaceholders()).doesNotContainKey("delta");
        assertThat(entryCall.stringPlaceholders().values()).noneMatch(v -> v.contains("&"));

        // Rendered text checks
        String entry = senderRecord.receivedMessages.stream()
                .filter(m -> m.contains("-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry containing -1 found in: " + senderRecord.receivedMessages));

        assertThat(entry).contains("System");
        assertThat(entry).contains("-1");
        assertThat(entry).contains("Open-world kill penalty");
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

        // Assert on message keys and substitutions
        RenderCall entryCall = recordingRegistry.findFirstCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        assertThat(entryCall.key()).isEqualTo("status.history-entry");
        assertThat(entryCall.stringPlaceholders().get("actor")).isEqualTo("Sistema");
        assertThat(entryCall.stringPlaceholders().get("reason")).isEqualTo("Penalización por muerte fuera de duelo");
        assertThat(entryCall.componentPlaceholders()).containsKey("delta");
        assertThat(entryCall.stringPlaceholders()).doesNotContainKey("delta");
        assertThat(entryCall.stringPlaceholders().values()).noneMatch(v -> v.contains("&"));

        assertThat(senderRecord.receivedMessages).isNotEmpty();
        String entry = senderRecord.receivedMessages.stream()
                .filter(m -> m.contains("-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry containing -1 found in: " + senderRecord.receivedMessages));

        assertThat(entry).contains("Sistema");
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

        assertThat(recordingRegistry.findFirstCall("status.history-empty")).isPresent();
        assertThat(senderRecord.receivedMessages).isNotEmpty();
        assertThat(senderRecord.receivedMessages.getFirst()).contains("CleanPlayer");
        assertThat(senderRecord.receivedMessages.get(1)).contains("No reputation events recorded");
    }

    @Test
    @DisplayName("SB-082: Unpaid viewer sees anonymous form; after reveal exists for viewer, same read shows rater name")
    void viewerWithoutRevealSeesAnonymousForm_afterRevealSeesName() {
        Player target = registerPlayer("TargetAlice");
        Player rater = registerPlayer("RaterBob");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());

        ReputationEvent savedEvent = reputationRepo.save(new ReputationEvent(
                0L,
                raterId,
                targetId,
                1,
                HonorKind.POSITIVE,
                500.0,
                "Outstanding team play",
                baseTime
        ));

        MockPlayerRecord viewer = new MockPlayerRecord("ViewerCharlie", false, "socialblueprint.show.others", "socialblueprint.view");
        RuntimeSnapshot snapshot = configManager.snapshot();

        // 1. ViewerCharlie has NOT paid to reveal the rater
        historyCommand.execute(viewer.player, new String[]{"TargetAlice"}, snapshot).join();

        RenderCall unrevealedCall = recordingRegistry.findLastCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        // Assert on message keys and substitutions: must show anonymous form, not name and not UUID
        assertThat(unrevealedCall.key()).isEqualTo("status.history-entry");
        assertThat(unrevealedCall.stringPlaceholders().get("actor")).isEqualTo("Anonymous");
        assertThat(unrevealedCall.stringPlaceholders().get("actor")).isNotEqualTo("RaterBob");
        assertThat(unrevealedCall.stringPlaceholders().get("actor")).isNotEqualTo(rater.getUniqueId().toString());

        // 2. Persist a reveal for ViewerCharlie and this event
        raterRevealRepo.saveRevealAsync(viewer.player.getUniqueId(), savedEvent.id(), rater.getUniqueId(), 100.0, baseTime).join();

        recordingRegistry.renderCalls.clear();

        // 3. ViewerCharlie reads the same history again
        historyCommand.execute(viewer.player, new String[]{"TargetAlice"}, snapshot).join();

        RenderCall revealedCall = recordingRegistry.findLastCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded after reveal"));

        // Assert on message keys and substitutions: must show rater's real name, not anonymous and not UUID
        assertThat(revealedCall.key()).isEqualTo("status.history-entry");
        assertThat(revealedCall.stringPlaceholders().get("actor")).isEqualTo("RaterBob");
        assertThat(revealedCall.stringPlaceholders().get("actor")).isNotEqualTo("Anonymous");
        assertThat(revealedCall.stringPlaceholders().get("actor")).isNotEqualTo(rater.getUniqueId().toString());
    }

    @Test
    @DisplayName("SB-082: Spanish locale renders anonymous rater as Anónimo when unrevealed")
    void unrevealedRaterInSpanishShowsAnonimo() {
        Player target = registerPlayer("TargetAliceEs");
        Player rater = registerPlayer("RaterBobEs");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());

        reputationRepo.save(new ReputationEvent(
                0L,
                raterId,
                targetId,
                1,
                HonorKind.POSITIVE,
                500.0,
                "Buen trabajo",
                baseTime
        ));

        configManager.set("language", "es");
        RuntimeSnapshot snapshot = configManager.snapshot();

        MockPlayerRecord viewer = new MockPlayerRecord("ViewerJuan", false, "socialblueprint.show.others", "socialblueprint.view");
        historyCommand.execute(viewer.player, new String[]{"TargetAliceEs"}, snapshot).join();

        RenderCall call = recordingRegistry.findLastCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        assertThat(call.key()).isEqualTo("status.history-entry");
        assertThat(call.stringPlaceholders().get("actor")).isEqualTo("Anónimo");
        assertThat(call.stringPlaceholders().values()).noneMatch(v -> v.contains("&"));
    }

    @Test
    @DisplayName("SB-082 / SB-083: Delta reaches renderer as component, and no substituted value contains raw & colour codes")
    void deltaReachesRendererAsComponent_noRawColorCodesInSubstitutedValues() {
        Player target = registerPlayer("TargetDelta");
        Player rater = registerPlayer("RaterDelta");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());

        // Event with positive delta (+2)
        reputationRepo.save(new ReputationEvent(
                0L, raterId, targetId, 2, HonorKind.POSITIVE, 500.0, "positive", baseTime.minusSeconds(20)
        ));
        // Event with negative delta (-3)
        reputationRepo.save(new ReputationEvent(
                0L, raterId, targetId, -3, HonorKind.NEGATIVE, 500.0, "negative", baseTime.minusSeconds(10)
        ));
        // Event with neutral/system delta (0)
        reputationRepo.save(new ReputationEvent(
                0L, null, targetId, 0, HonorKind.SYSTEM_KILL, 0.0, "neutral", baseTime
        ));

        MockPlayerRecord viewer = new MockPlayerRecord("ViewerDelta", false, "socialblueprint.show.others", "socialblueprint.view");
        RuntimeSnapshot snapshot = configManager.snapshot();

        historyCommand.execute(viewer.player, new String[]{"TargetDelta"}, snapshot).join();

        List<RenderCall> entryCalls = recordingRegistry.findCalls("status.history-entry");
        assertThat(entryCalls).hasSize(3);

        for (RenderCall call : entryCalls) {
            // Delta MUST reach renderer as a Component placeholder
            assertThat(call.componentPlaceholders()).containsKey("delta");
            assertThat(call.componentPlaceholders().get("delta")).isNotNull();

            // Delta MUST NOT be in string substitutions (guard against string concatenation like "&a+" + delta)
            assertThat(call.stringPlaceholders()).doesNotContainKey("delta");

            // No substituted value may contain a raw '&' colour code
            for (Map.Entry<String, String> entry : call.stringPlaceholders().entrySet()) {
                assertThat(entry.getValue())
                        .as("Placeholder '%s' value '%s' must not contain raw '&' colour code", entry.getKey(), entry.getValue())
                        .doesNotContain("&");
            }
        }
    }

    @Test
    @DisplayName("SB-084 / T-126: Administrator always sees rater identity without paying")
    void adminViewerAlwaysSeesRaterIdentityWithoutPaying() {
        Player target = registerPlayer("TargetAdminTest");
        Player rater = registerPlayer("RaterAdminTest");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());

        reputationRepo.save(new ReputationEvent(
                0L, raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Great work", baseTime
        ));

        // Viewer has administrator permission
        MockPlayerRecord adminViewer = new MockPlayerRecord("AdminViewer", false, "socialblueprint.admin", "socialblueprint.show.others");
        RuntimeSnapshot snapshot = configManager.snapshot();

        historyCommand.execute(adminViewer.player, new String[]{"TargetAdminTest"}, snapshot).join();

        RenderCall call = recordingRegistry.findLastCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        // Admin sees real name even without any reveal record
        assertThat(call.stringPlaceholders().get("actor")).isEqualTo("RaterAdminTest");
    }

    @Test
    @DisplayName("T-124: Rater viewing their own rating sees their own name without paying")
    void raterViewingOwnRatingSeesOwnNameWithoutPaying() {
        Player target = registerPlayer("TargetSelf");
        Player rater = registerPlayer("RaterSelf");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());

        reputationRepo.save(new ReputationEvent(
                0L, raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Self inspection", baseTime
        ));

        // RaterSelf views the history of target
        MockPlayerRecord raterViewer = new MockPlayerRecord("RaterSelf", false, "socialblueprint.show.others", "socialblueprint.view");
        RuntimeSnapshot snapshot = configManager.snapshot();

        historyCommand.execute(raterViewer.player, new String[]{"TargetSelf"}, snapshot).join();

        RenderCall call = recordingRegistry.findLastCall("status.history-entry")
                .orElseThrow(() -> new AssertionError("No status.history-entry call recorded"));

        // Rater sees their own name on their own rating
        assertThat(call.stringPlaceholders().get("actor")).isEqualTo("RaterSelf");
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
