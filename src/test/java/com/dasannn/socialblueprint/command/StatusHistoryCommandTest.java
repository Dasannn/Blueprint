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
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
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
    private StatusHistoryCommand historyCommand;

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new HashMap<>();
    private final Instant baseTime = Instant.parse("2026-10-01T00:06:25.535503500Z");

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

        historyCommand = new StatusHistoryCommand(
                profileService, reputationRepo, recordingRegistry, Runnable::run);
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
    void allHistoryReadersSeeFilteredReasonAndRevokeAdminsSeeIds() {
        Player target = registerPlayer("FilteredTarget");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        ReputationEvent rating = reputationRepo.save(new ReputationEvent(PlayerId.of(UUID.randomUUID()),
                targetId, 1, HonorKind.POSITIVE, 500, "IMBÉCIL, idiot &a", baseTime));
        var admin = new MockPlayerRecord("Admin", false, "socialblueprint.admin.revoke");
        var console = new MockSenderRecord("Console", true);
        for (CommandSender reader : List.of(admin.player, console.sender)) {
            recordingRegistry.renderCalls.clear();
            historyCommand.execute(reader, new String[]{"FilteredTarget"}, configManager.snapshot()).join();
            assertThat(recordingRegistry.findCalls("status.history-entry")).singleElement().satisfies(call ->
                    assertThat(call.stringPlaceholders()).containsEntry("reason", "bobba, bobba"));
            assertThat(recordingRegistry.findCalls("honor.rating-id")).singleElement().satisfies(call ->
                    assertThat(call.stringPlaceholders()).containsEntry("id", String.valueOf(rating.id())));
        }
        assertThat(reputationRepo.findByTarget(targetId).getFirst().reason()).isEqualTo("IMBÉCIL, idiot &a");
    }

    @Test
    @DisplayName("SB-085: System, player and revealed ratings have only date, delta and reason in both languages")
    void historyHasNoActorEvenAfterReveal() {
        Player target = registerPlayer("Target");
        Player rater = registerPlayer("Rater");
        PlayerId targetId = PlayerId.of(target.getUniqueId());
        PlayerId raterId = PlayerId.of(rater.getUniqueId());
        reputationRepo.save(new ReputationEvent(
                0L, null, targetId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime));
        ReputationEvent rating = reputationRepo.save(new ReputationEvent(
                0L, raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "kill-penalty.reason", baseTime));
        for (String language : List.of("en", "es")) {
            MockPlayerRecord viewer = new MockPlayerRecord("Viewer-" + language, false, "socialblueprint.admin.adjust");
            configManager.set("language", language);
            RuntimeSnapshot snapshot = configManager.snapshot();
            for (boolean revealed : List.of(false, true)) {
                if (revealed) {
                    raterRevealRepo.saveRevealAsync(viewer.player.getUniqueId(), rating.id(),
                            rater.getUniqueId(), 100.0, baseTime).join();
                }
                recordingRegistry.renderCalls.clear();
                historyCommand.execute(viewer.player, new String[]{"Target"}, snapshot).join();
                List<RenderCall> calls = recordingRegistry.findCalls("status.history-entry");
                assertThat(calls).hasSize(2);
                for (RenderCall call : calls) {
                    assertThat(call.stringPlaceholders()).containsOnlyKeys("time", "reason");
                    assertThat(call.stringPlaceholders()).containsEntry("time", "2026-10-01");
                    assertThat(call.componentPlaceholders()).containsOnlyKeys("delta");
                    boolean system = call.stringPlaceholders().get("reason")
                            .equals(recordingRegistry.getRaw(snapshot, "kill-penalty.reason"));
                    assertThat(call.stringPlaceholders()).containsEntry("reason", system
                            ? recordingRegistry.getRaw(snapshot, "kill-penalty.reason") : "kill-penalty.reason");
                    assertThat(call.componentPlaceholders().get("delta"))
                            .isEqualTo(Component.text(system ? "-1" : "+1", system
                                    ? net.kyori.adventure.text.format.NamedTextColor.RED
                                    : net.kyori.adventure.text.format.NamedTextColor.GREEN));
                }
                assertThat(recordingRegistry.getRaw(snapshot, "status.history-entry"))
                        .contains("{time}", "{delta}", "{reason}").doesNotContain("{actor}");
            }
        }
    }

    @Test
    void consoleCanReadHistoryWithoutPermissions() {
        registerPlayer("Target");
        MockSenderRecord console = new MockSenderRecord("Console", false);
        historyCommand.execute(console.sender, new String[]{"Target"}, configManager.snapshot()).join();
        assertThat(recordingRegistry.findFirstCall("status.history-header").orElseThrow().stringPlaceholders())
                .containsEntry("player", "Target");
        assertThat(recordingRegistry.findCalls("commands.no-permission")).isEmpty();
    }

    @Test
    void adminAdjustAloneAllowsSelfAndOtherHistory() {
        registerPlayer("Admin");
        registerPlayer("Target");
        MockPlayerRecord admin = new MockPlayerRecord("Admin", false, "socialblueprint.admin.adjust");
        for (String[] args : List.of(new String[0], new String[]{"Target"})) {
            recordingRegistry.renderCalls.clear();
            historyCommand.execute(admin.player, args, configManager.snapshot()).join();
            assertThat(recordingRegistry.findFirstCall("status.history-header").orElseThrow().stringPlaceholders())
                    .containsEntry("player", args.length == 0 ? "Admin" : "Target");
            assertThat(recordingRegistry.findCalls("commands.no-permission")).isEmpty();
        }
    }

    @Test
    void ordinaryPermissionsCannotReadSelfOrOtherHistory() {
        registerPlayer("Viewer");
        registerPlayer("Target");
        for (String permission : List.of("socialblueprint.show", "socialblueprint.show.others",
                "socialblueprint.view", "socialblueprint.view.reputation", "pstatus.show", "pstatus.viewReputation")) {
            MockPlayerRecord viewer = new MockPlayerRecord("Viewer", false, permission);
            for (String[] args : List.of(new String[0], new String[]{"Target"})) {
                recordingRegistry.renderCalls.clear();
                historyCommand.execute(viewer.player, args, configManager.snapshot()).join();
                assertThat(recordingRegistry.findCalls("commands.no-permission")).hasSize(1);
                assertThat(recordingRegistry.findCalls("status.history-header")).isEmpty();
                assertThat(recordingRegistry.findCalls("status.history-entry")).isEmpty();
            }
        }
    }

    @Test
    void historyCompletionIsOnlyForAdminsAndNonPlayers() {
        Player target = registerPlayer("Target");
        StatusCommandExecutor executor = new StatusCommandExecutor(
                configManager, recordingRegistry, profileService, null, Runnable::run, () -> List.of(target));
        MockPlayerRecord admin = new MockPlayerRecord("Admin", false, "socialblueprint.admin.adjust");
        MockSenderRecord console = new MockSenderRecord("Console", false);
        for (CommandSender sender : List.of(admin.player, console.sender)) {
            assertThat(executor.onTabComplete(sender, null, "status", new String[]{""})).contains("history");
            assertThat(executor.onTabComplete(sender, null, "status", new String[]{"hi"})).containsExactly("history");
            assertThat(executor.onTabComplete(sender, null, "status", new String[]{"history", ""}))
                    .containsExactly("Target");
        }
        for (String permission : List.of("socialblueprint.show", "socialblueprint.show.others",
                "socialblueprint.view", "socialblueprint.view.reputation", "socialblueprint.admin.config")) {
            MockPlayerRecord viewer = new MockPlayerRecord("Viewer", false, permission);
            assertThat(executor.onTabComplete(viewer.player, null, "status", new String[]{""}))
                    .doesNotContain("history");
            assertThat(executor.onTabComplete(viewer.player, null, "status", new String[]{"hi"})).isEmpty();
            assertThat(executor.onTabComplete(viewer.player, null, "status", new String[]{"history", ""})).isEmpty();
        }
    }

    @Test
    void emptyHistoryUsesMessageKey() {
        registerPlayer("CleanPlayer");
        MockSenderRecord viewer = new MockSenderRecord("Viewer", false, "socialblueprint.show.others");
        historyCommand.execute(viewer.sender, new String[]{"CleanPlayer"}, configManager.snapshot()).join();
        assertThat(recordingRegistry.findFirstCall("status.history-empty")).isPresent();
        assertThat(recordingRegistry.findCalls("status.history-entry")).isEmpty();
    }

    @Test
    void nonsenseNameUsesNotFoundKey() {
        MockSenderRecord viewer = new MockSenderRecord("Viewer", false, "socialblueprint.show.others");
        historyCommand.execute(viewer.sender, new String[]{"asdkjhasd"}, configManager.snapshot()).join();
        assertThat(recordingRegistry.findFirstCall("status.not-found").orElseThrow().stringPlaceholders())
                .containsEntry("player", "asdkjhasd");
        assertThat(recordingRegistry.findCalls("status.history-header")).isEmpty();
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
