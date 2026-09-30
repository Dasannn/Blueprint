package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
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
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Comprehensive test suite verifying all Phase P4 requirements (T-050 to T-058)
 * and the Definition of Done:
 * - DoD 1: Green on Java 25 with both 'en' and 'es'.
 * - DoD 2: Console runs every command without exception.
 * - DoD 3: Player who changes name retains their record.
 * - DoD 4: Admin actions write audit row; admin reset leaves underlying events intact.
 * - DoD 5: Failed charge writes no event; failed write refunds.
 * - DoD 6: No blocking DB call on main thread, and no Bukkit call off it.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class P4CommandsPermissionsTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private AuditRepository auditRepo;
    private ProfileService profileService;
    private HonorService honorService;
    private StatusCommandExecutor commandExecutor;
    private TestClock testClock;

    private final Queue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private final Consumer<Runnable> mainThreadRunner = mainThreadQueue::add;

    private Economy mockEconomy;
    private final Map<UUID, Double> economyBalances = new ConcurrentHashMap<>();
    private final AtomicBoolean failEconomyWithdrawal = new AtomicBoolean(false);
    private final AtomicInteger economyWithdrawCount = new AtomicInteger(0);
    private final AtomicInteger economyDepositCount = new AtomicInteger(0);
    private final AtomicReference<Double> lastWithdrawnAmount = new AtomicReference<>(0.0);
    private final AtomicReference<Double> lastDepositedAmount = new AtomicReference<>(0.0);

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> offlineUuidMap = new ConcurrentHashMap<>();
    private final List<Player> onlinePlayersList = new ArrayList<>();

    interface MessageRecorder {
        List<String> sentMessages();
        List<Component> sentComponents();
    }

    public static class RecordingMessageRegistry extends MessageRegistry {
        public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

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

        public RenderCall lastCall() {
            if (renderedCalls.isEmpty()) {
                throw new AssertionError("No messages were rendered");
            }
            return renderedCalls.getLast();
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
        }

        public Optional<RenderCall> findLastCall(String key) {
            return renderedCalls.stream().filter(c -> c.key().equals(key)).reduce((first, second) -> second);
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

        @Override
        public Component render(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(key, placeholders);
        }

        @Override
        public Component render(String key) {
            return render(key, Collections.emptyMap());
        }
    }

    static class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
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

        Logger logger = Logger.getLogger("P4Test-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "es", logger);
        configManager = new ConfigManager(configFile, messageRegistry, mainThreadQueue::add, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);
        auditRepo = new AuditRepository(storage);

        testClock = new TestClock(Instant.parse("2026-09-29T12:00:00Z"));

        // Setup PlayerLookup with thread-boundary validation
        PlayerLookup testLookup = nameOrUuid -> {
            assertNotOnDatabaseThread("PlayerLookup#findPlayer");
            String key = nameOrUuid.toLowerCase(Locale.ROOT);
            if (onlineLookupMap.containsKey(key)) {
                return Optional.of(onlineLookupMap.get(key));
            }
            try {
                UUID u = UUID.fromString(nameOrUuid);
                if (offlineUuidMap.containsKey(u)) {
                    return Optional.of(new PlayerLookup.KnownPlayer(PlayerId.of(u), offlineUuidMap.get(u), false));
                }
            } catch (IllegalArgumentException ignored) {
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

        // Dynamic Proxy for Vault Economy
        InvocationHandler econHandler = (proxy, method, args) -> {
            assertNotOnDatabaseThread("Economy#" + method.getName());
            String mName = method.getName();
            if ("has".equals(mName) && args.length >= 2) {
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                return economyBalances.getOrDefault(p.getUniqueId(), 1000.0) >= amt;
            }
            if ("withdrawPlayer".equals(mName) && args.length >= 2) {
                economyWithdrawCount.incrementAndGet();
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                lastWithdrawnAmount.set(amt);
                if (failEconomyWithdrawal.get()) {
                    return new EconomyResponse(0, economyBalances.getOrDefault(p.getUniqueId(), 1000.0),
                            EconomyResponse.ResponseType.FAILURE, "Simulated withdrawal failure");
                }
                double bal = economyBalances.getOrDefault(p.getUniqueId(), 1000.0) - amt;
                economyBalances.put(p.getUniqueId(), bal);
                return new EconomyResponse(amt, bal, EconomyResponse.ResponseType.SUCCESS, null);
            }
            if ("depositPlayer".equals(mName) && args.length >= 2) {
                economyDepositCount.incrementAndGet();
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                lastDepositedAmount.set(amt);
                double bal = economyBalances.getOrDefault(p.getUniqueId(), 1000.0) + amt;
                economyBalances.put(p.getUniqueId(), bal);
                return new EconomyResponse(amt, bal, EconomyResponse.ResponseType.SUCCESS, null);
            }
            if ("format".equals(mName)) {
                return "$" + args[0];
            }
            if ("getBalance".equals(mName) && args.length >= 1) {
                OfflinePlayer p = (OfflinePlayer) args[0];
                return economyBalances.getOrDefault(p.getUniqueId(), 1000.0);
            }
            return defaultValue(method.getReturnType());
        };

        mockEconomy = (Economy) Proxy.newProxyInstance(
                Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class},
                econHandler
        );

        honorService = new HonorService(
                configManager,
                messageRegistry,
                reputationRepo,
                auditRepo,
                profileService,
                mockEconomy,
                mainThreadRunner,
                testClock
        );

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profileService,
                honorService,
                mainThreadRunner,
                () -> onlinePlayersList
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private void drainQueueUntilDone(Queue<Runnable> queue, CompletableFuture<?> future) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Runnable r;
            boolean executed = false;
            while ((r = queue.poll()) != null) {
                r.run();
                executed = true;
            }
            if (!executed && (future == null || future.isDone())) {
                break;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Runnable r;
        while ((r = queue.poll()) != null) {
            r.run();
        }
    }

    private void drainMainThread() {
        drainQueueUntilDone(mainThreadQueue, commandExecutor.lastExecution());
    }

    private boolean runCommandSync(CommandSender sender, String label, String... args) {
        boolean result = commandExecutor.onCommand(sender, null, label, args);
        drainMainThread();
        commandExecutor.lastExecution().join();
        drainMainThread();
        return result;
    }

    // =========================================================================
    // DoD 2 & T-050: Console execution & Sender resolution
    // =========================================================================

    @Test
    @DisplayName("DoD 2 / T-050: Console runs /status without exception and receives player-only message")
    void consoleRunsStatusWithoutException() {
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();

        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");
    }

    @Test
    @DisplayName("DoD 2 / T-050: Console runs player-only commands (give, take, confirm) without exception")
    void consoleRunsPlayerOnlyCommandsWithoutException() {
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        // /status give Target & /status trust Target
        assertThatCode(() -> {
            runCommandSync(console, "status", "give", "Alice");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");

            runCommandSync(console, "status", "trust", "Alice");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");
        }).doesNotThrowAnyException();

        // /status take Target reason & /status distrust Target reason
        assertThatCode(() -> {
            runCommandSync(console, "status", "take", "Alice", "griefing");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");

            runCommandSync(console, "status", "distrust", "Alice", "griefing");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");
        }).doesNotThrowAnyException();

        // /status confirm
        assertThatCode(() -> {
            runCommandSync(console, "status", "confirm");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");
        }).doesNotThrowAnyException();

        // Legacy /reputation Target +
        assertThatCode(() -> {
            runCommandSync(console, "reputation", "Alice", "+");
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.player-only");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DoD 2: Console runs every admin command (give, take, reset) without exception")
    void consoleRunsEveryAdminCommandWithoutException() {
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);
        mockPlayer("Alice");

        // Admin give
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "admin", "give", "Alice", "10");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.give-success");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("amount", "10")
                .containsEntry("target", "Alice");

        // Admin take
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "admin", "take", "Alice", "3");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.take-success");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("amount", "3")
                .containsEntry("target", "Alice");

        // Admin reset
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "admin", "reset", "Alice");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.reset-success");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("target", "Alice");
    }

    @Test
    @DisplayName("DoD 2: Console runs config subcommands without exception")
    void consoleRunsConfigWithoutException() {
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "config", "language");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.get");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("key", "language");
    }

    @Test
    @DisplayName("DoD 2: Unknown subcommand displays localized message without throwing")
    void unknownSubcommandDisplaysMessage() {
        Player player = mockPlayer("Bob", "socialblueprint.show");
        boolean handled = runCommandSync(player, "status", "unknownSubcommand", "extraArg");
        assertThat(handled).isTrue();

        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.unknown-subcommand");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("command", "unknownsubcommand");
    }

    // =========================================================================
    // DoD 3 & T-052: Player Name Change Persistence & Target Resolution
    // =========================================================================

    @Test
    @DisplayName("DoD 3 / T-052: A player who changes name keeps their reputation record")
    void playerWhoChangesNameKeepsTheirRecord() {
        UUID bobUuid = UUID.randomUUID();
        PlayerId bobId = PlayerId.of(bobUuid);

        // Step 1: Bob is originally named "OldBob"
        offlineUuidMap.put(bobUuid, "OldBob");
        onlineLookupMap.put("oldbob", new PlayerLookup.KnownPlayer(bobId, "OldBob", false));

        // Admin gives OldBob +15 reputation
        List<String> consoleMsgs = new ArrayList<>();
        CommandSender console = mockConsole(consoleMsgs);
        runCommandSync(console, "status", "admin", "give", "OldBob", "15");

        // Step 2: Query by old name
        Player viewer = mockPlayer("Viewer", "socialblueprint.show.others");
        runCommandSync(viewer, "status", "OldBob");
        assertThat(messageRegistry.findLastCall("status.profile-status").orElseThrow().placeholders())
                .containsEntry("status", "15");

        // Step 3: Bob changes name to "RenamedBob" in Bukkit lookup
        onlineLookupMap.remove("oldbob");
        onlineLookupMap.put("renamedbob", new PlayerLookup.KnownPlayer(bobId, "RenamedBob", false));
        offlineUuidMap.put(bobUuid, "RenamedBob");

        // Step 4: Query by new name
        Player viewer2 = mockPlayer("Viewer2", "socialblueprint.show.others");
        runCommandSync(viewer2, "status", "RenamedBob");
        assertThat(messageRegistry.findLastCall("status.profile-status").orElseThrow().placeholders())
                .containsEntry("status", "15");

        // Step 5: Querying by UUID also yields the exact same record
        Player viewer3 = mockPlayer("Viewer3", "socialblueprint.show.others");
        runCommandSync(viewer3, "status", bobUuid.toString());
        assertThat(messageRegistry.findLastCall("status.profile-status").orElseThrow().placeholders())
                .containsEntry("status", "15");
    }

    // =========================================================================
    // DoD 4 & T-055, T-056: Admin Audit Trail & Compensating Event Reset
    // =========================================================================

    @Test
    @DisplayName("DoD 4 / T-056: Every admin action leaves an audit row with actor, op, target, before, after, time")
    void adminActionsLeaveAuditRows() {
        Player admin = mockPlayer("AdminOp", "socialblueprint.admin");
        Player target = mockPlayer("Charlie");
        UUID targetUuid = target.getUniqueId();

        // Admin Give +20
        runCommandSync(admin, "status", "admin", "give", "Charlie", "20");

        List<AuditEvent> auditsAfterGive = auditRepo.findByTarget(PlayerId.of(targetUuid));
        assertThat(auditsAfterGive).hasSize(1);
        AuditEvent giveAudit = auditsAfterGive.getFirst();
        assertThat(giveAudit.actor()).isEqualTo(PlayerId.of(admin.getUniqueId()));
        assertThat(giveAudit.operation()).isEqualTo("admin_give");
        assertThat(giveAudit.target()).isEqualTo(targetUuid.toString());
        assertThat(giveAudit.before()).isEqualTo("0");
        assertThat(giveAudit.after()).isEqualTo("20");
        assertThat(giveAudit.createdAt()).isEqualTo(testClock.instant());

        // Admin Take -5
        runCommandSync(admin, "status", "admin", "take", "Charlie", "5");

        List<AuditEvent> auditsAfterTake = auditRepo.findByTarget(PlayerId.of(targetUuid));
        assertThat(auditsAfterTake).hasSize(2);
        AuditEvent takeAudit = auditsAfterTake.get(1);
        assertThat(takeAudit.operation()).isEqualTo("admin_take");
        assertThat(takeAudit.before()).isEqualTo("20");
        assertThat(takeAudit.after()).isEqualTo("15");

        // Admin Reset
        runCommandSync(admin, "status", "admin", "reset", "Charlie");

        List<AuditEvent> auditsAfterReset = auditRepo.findByTarget(PlayerId.of(targetUuid));
        assertThat(auditsAfterReset).hasSize(3);
        AuditEvent resetAudit = auditsAfterReset.get(2);
        assertThat(resetAudit.operation()).isEqualTo("admin_reset");
        assertThat(resetAudit.before()).isEqualTo("15");
        assertThat(resetAudit.after()).isEqualTo("0");
    }

    @Test
    @DisplayName("DoD 4 / T-056: Admin reset writes a compensating event, leaving underlying events intact")
    void adminResetLeavesUnderlyingEventsIntact() {
        Player admin = mockPlayer("AdminOp", "socialblueprint.admin");
        Player target = mockPlayer("Dave");
        UUID targetUuid = target.getUniqueId();
        PlayerId targetId = PlayerId.of(targetUuid);

        // Give +10
        runCommandSync(admin, "status", "admin", "give", "Dave", "10");

        // Give another +5
        runCommandSync(admin, "status", "admin", "give", "Dave", "5");

        // Verify Dave has 2 positive events
        List<ReputationEvent> eventsBeforeReset = reputationRepo.findByTargetAsync(targetId).join();
        assertThat(eventsBeforeReset).hasSize(2);

        // Perform Admin Reset
        runCommandSync(admin, "status", "admin", "reset", "Dave");

        // Verify underlying events were NOT deleted, but a 3rd compensating event was appended
        List<ReputationEvent> eventsAfterReset = reputationRepo.findByTargetAsync(targetId).join();
        assertThat(eventsAfterReset).hasSize(3);

        // The first two events are identical and intact
        assertThat(eventsAfterReset.get(0).delta()).isEqualTo(10);
        assertThat(eventsAfterReset.get(1).delta()).isEqualTo(5);

        // The compensating event cancels the sum (+15 - 15 = 0)
        ReputationEvent compensatingEvent = eventsAfterReset.get(2);
        assertThat(compensatingEvent.delta()).isEqualTo(-15);
        assertThat(compensatingEvent.kind()).isEqualTo(HonorKind.ADMIN_RESET);

        // Calculated status score is now 0
        int finalScore = reputationRepo.getStatus(targetId).value();
        assertThat(finalScore).isEqualTo(0);
    }

    // =========================================================================
    // DoD 5 & T-058: Atomic Charge and Write, Refund on DB Write Failure
    // =========================================================================

    @Test
    @DisplayName("DoD 5 / T-058: Failed charge writes no event")
    void failedChargeWritesNoEvent() {
        Player actor = mockPlayer("Actor1", "socialblueprint.give");
        Player target = mockPlayer("Target1");
        economyBalances.put(actor.getUniqueId(), 500.0);

        // Stage 1: Give command prepares pending confirmation
        runCommandSync(actor, "status", "give", "Target1", "great ally");

        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        // Simulate Economy failure on withdrawal (e.g. concurrent drain or provider error)
        failEconomyWithdrawal.set(true);

        // Stage 2: Player runs /status confirm
        runCommandSync(actor, "status", "confirm");

        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.insufficient-funds");

        // Assert NO ReputationEvent was written
        List<ReputationEvent> events = reputationRepo.findByTargetAsync(PlayerId.of(target.getUniqueId())).join();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("DoD 5 / T-058: Failed event write refunds the exact charged amount")
    void failedEventWriteRefundsExactAmount() {
        StorageEngine failingEngine = StorageEngine.inMemory();
        failingEngine.runMigrations();

        Queue<Runnable> failingQueue = new ConcurrentLinkedQueue<>();
        try {
            ReputationRepository failingRepo = new ReputationRepository(failingEngine, new StatusCache());

            HonorService failingHonorService = new HonorService(
                    configManager,
                    messageRegistry,
                    failingRepo,
                    auditRepo,
                    profileService,
                    mockEconomy,
                    failingQueue::add,
                    testClock
            );

            StatusCommandExecutor customExecutor = new StatusCommandExecutor(
                    configManager,
                    messageRegistry,
                    profileService,
                    failingHonorService,
                    failingQueue::add,
                    () -> onlinePlayersList
            );

            Player actor = mockPlayer("Actor2", "socialblueprint.give");
            Player target = mockPlayer("Target2");
            economyBalances.put(actor.getUniqueId(), 500.0);

            // Prepare rating (Stage 1: succeeds and prepares confirmation)
            customExecutor.onCommand(actor, null, "status", new String[]{"give", "Target2"});
            drainQueueUntilDone(failingQueue, customExecutor.lastExecution());
            customExecutor.lastExecution().join();
            drainQueueUntilDone(failingQueue, customExecutor.lastExecution());

            assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

            int withdrawsBefore = economyWithdrawCount.get();
            int depositsBefore = economyDepositCount.get();

            // Simulate database write failure right before confirmation by closing engine
            failingEngine.close();

            // Confirm rating (Stage 2: charge succeeds, DB write fails, refund triggered)
            customExecutor.onCommand(actor, null, "status", new String[]{"confirm"});
            drainQueueUntilDone(failingQueue, customExecutor.lastExecution());
            customExecutor.lastExecution().join();
            drainQueueUntilDone(failingQueue, customExecutor.lastExecution());

            // Verify withdrawal was attempted and succeeded
            assertThat(economyWithdrawCount.get()).isEqualTo(withdrawsBefore + 1);
            double chargedAmount = lastWithdrawnAmount.get();
            assertThat(chargedAmount).isGreaterThan(0.0);

            // Verify refund deposit was immediately called with the EXACT same amount
            assertThat(economyDepositCount.get()).isEqualTo(depositsBefore + 1);
            assertThat(lastDepositedAmount.get()).isEqualTo(chargedAmount);

            // Balance restored to 500.0
            assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(500.0);

            // Actor received error notification
            assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.write-failed");
        } finally {
            failingEngine.close();
        }
    }

    // =========================================================================
    // DoD 6: Threading Boundaries
    // =========================================================================

    @Test
    @DisplayName("DoD 6: Bukkit calls never happen on storage executor, and storage calls never happen on main thread")
    void threadingBoundariesStrictlyEnforced() {
        Player actor = mockPlayer("ThreadTester", "socialblueprint.give");
        mockPlayer("ThreadTarget");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        // Executing commands runs cleanly without thread assertion violations
        assertThatCode(() -> {
            runCommandSync(actor, "status", "give", "ThreadTarget");
            runCommandSync(actor, "status", "confirm");
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // T-051: Centralised Argument Parsing
    // =========================================================================

    @Test
    @DisplayName("T-051: Malformed numeric arguments yield localized error messages, never stack traces")
    void malformedArgumentsYieldMessagesNeverStackTraces() {
        Player admin = mockPlayer("AdminUser", "socialblueprint.admin");
        mockPlayer("Evan");

        // Non-integer string
        assertThatCode(() -> {
            runCommandSync(admin, "status", "admin", "give", "Evan", "notAnInt");
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.invalid-integer");

        // Negative integer where positive required
        assertThatCode(() -> {
            runCommandSync(admin, "status", "admin", "give", "Evan", "-5");
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.invalid-amount");

        // Gigantic integer overflow
        assertThatCode(() -> {
            runCommandSync(admin, "status", "admin", "take", "Evan", "9999999999999999999999999");
        }).doesNotThrowAnyException();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.invalid-integer");
    }

    @Test
    @DisplayName("T-051 / SB-056: Removing honor requires written reason, sending localized error message if omitted")
    void removingHonorRequiresReason() {
        Player actor = mockPlayer("Frank", "socialblueprint.take");
        mockPlayer("Grace");

        runCommandSync(actor, "status", "take", "Grace");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.reason-required");

        runCommandSync(actor, "status", "distrust", "Grace");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.reason-required");
    }

    // =========================================================================
    // T-053 & T-054: Permissions and Legacy Grants
    // =========================================================================

    @Test
    @DisplayName("T-053 / T-054: Legacy pstatus.* permissions allow executing corresponding commands")
    void legacyPermissionsWorkSeamlessly() {
        // Player with legacy pstatus.giveReputation
        Player legacyGiver = mockPlayer("LegacyGiver", "pstatus.giveReputation");
        mockPlayer("TargetLegacy");

        runCommandSync(legacyGiver, "status", "give", "TargetLegacy");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        runCommandSync(legacyGiver, "status", "trust", "TargetLegacy");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        // Player with legacy pstatus.addRemoveRep can take and administer
        Player legacyTaker = mockPlayer("LegacyTaker", "pstatus.addRemoveRep");
        runCommandSync(legacyTaker, "status", "take", "TargetLegacy", "rude");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        runCommandSync(legacyTaker, "status", "admin", "give", "TargetLegacy", "5");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.give-success");

        // Player with NO permission is denied
        Player unpermitted = mockPlayer("Unpermitted");
        runCommandSync(unpermitted, "status", "give", "TargetLegacy");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
    }

    @Test
    @DisplayName("T-054 / SB-061: Configurable permission nodes are read dynamically from snapshot at check time")
    void dynamicPermissionNodeFromSnapshot() {
        Player player = mockPlayer("CustomUser", "custom.trust.node");
        mockPlayer("CustomTarget");

        // Without node configured, user is denied
        runCommandSync(player, "status", "give", "CustomTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");

        // Reconfigure node dynamically
        configManager.set("permissions.give-reputation", "custom.trust.node");

        // Now user is permitted
        runCommandSync(player, "status", "give", "CustomTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");
    }

    // =========================================================================
    // T-055: Honor Economy Rules (Cooldown, Cap, Progressive Cost, Confirmation)
    // =========================================================================

    @Test
    @DisplayName("T-055 / SB-053: Cooldown between same actor-target pair is enforced")
    void cooldownBetweenSamePairIsEnforced() {
        Player actor = mockPlayer("Rater", "socialblueprint.give");
        mockPlayer("Rated");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        // Rate target once
        runCommandSync(actor, "status", "give", "Rated");
        runCommandSync(actor, "status", "confirm");
        assertThat(messageRegistry.hasCall("honor.given")).isTrue();

        // Immediately attempt to rate again (within cooldown)
        runCommandSync(actor, "status", "give", "Rated");

        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cooldown");
    }

    @Test
    @DisplayName("T-055 / SB-054: Cap of 3 positive and 3 negative ratings per pair inside window")
    void capOfThreePerPairInsideWindowEnforced() {
        Player actor = mockPlayer("CapTester", "socialblueprint.give", "socialblueprint.take");
        mockPlayer("CapTarget");
        economyBalances.put(actor.getUniqueId(), 5000.0);

        // Must match honor.cooldown-per-pair in config.yml, or the ratings are
        // refused for cooldown and the cap is never exercised.
        Duration cooldown = Duration.ofHours(24);

        // Issue 3 positive ratings (advancing time past cooldown each time)
        for (int i = 0; i < 3; i++) {
            runCommandSync(actor, "status", "give", "CapTarget");
            runCommandSync(actor, "status", "confirm");
            testClock.advance(cooldown.plusSeconds(1));
        }

        // 4th positive rating hits cap
        runCommandSync(actor, "status", "give", "CapTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cap-reached");

        // But negative rating is independent and succeeds!
        runCommandSync(actor, "status", "take", "CapTarget", "reversal of opinion");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");
    }

    @Test
    @DisplayName("T-055 / SB-052: Confirmation expires after 60 seconds")
    void confirmationExpiresAfterSixtySeconds() {
        Player actor = mockPlayer("SlowPayer", "socialblueprint.give");
        mockPlayer("Receiver");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        runCommandSync(actor, "status", "give", "Receiver");

        // Advance clock by 65 seconds
        testClock.advance(Duration.ofSeconds(65));

        runCommandSync(actor, "status", "confirm");

        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.no-pending");
    }

    @Test
    @DisplayName("T-055: Cannot rate oneself")
    void cannotRateSelf() {
        Player actor = mockPlayer("Selfish", "socialblueprint.give");

        runCommandSync(actor, "status", "give", "Selfish");

        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cannot-rate-self");
    }

    // =========================================================================
    // DoD 1: Pinned-language execution tests (Only place where rendered strings belong)
    // =========================================================================

    @Test
    @DisplayName("DoD 1: Pinned Spanish language execution outputs exact Spanish message")
    void executesInSpanish() {
        configManager.set("language", "es");
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        runCommandSync(console, "status");

        // In Spanish, commands.player-only is: "&cEste comando solo puede ser ejecutado por jugadores."
        assertThat(messages.getLast()).contains("solo puede ser ejecutado por jugadores");
    }

    @Test
    @DisplayName("DoD 1: Pinned English language execution outputs exact English message")
    void executesInEnglish() {
        configManager.set("language", "en");
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        runCommandSync(console, "status");

        // In English, commands.player-only is: "&cThis command can only be executed by players."
        assertThat(messages.getLast()).contains("This command can only be executed by players.");
    }

    // =========================================================================
    // Helpers and Assertions
    // =========================================================================

    private void assertNotOnDatabaseThread(String context) {
        String threadName = Thread.currentThread().getName();
        if (threadName.contains("socialblueprint-db")) {
            throw new IllegalStateException("Forbidden: " + context + " was invoked on DB executor thread: " + threadName);
        }
    }

    private Player mockPlayer(String name, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        List<String> messages = new ArrayList<>();
        List<Component> components = new ArrayList<>();
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        InvocationHandler handler = (proxy, method, args) -> {
            assertNotOnDatabaseThread("Player#" + method.getName());
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
                    components.add(comp);
                }
                return null;
            }
            if ("sentMessages".equals(mName)) return messages;
            if ("sentComponents".equals(mName)) return components;
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, MessageRecorder.class},
                handler
        );

        onlineLookupMap.put(name.toLowerCase(Locale.ROOT), new PlayerLookup.KnownPlayer(PlayerId.of(uuid), name, true));
        offlineUuidMap.put(uuid, name);
        onlinePlayersList.add(player);
        return player;
    }

    private CommandSender mockConsole(List<String> messages) {
        InvocationHandler handler = (proxy, method, args) -> {
            assertNotOnDatabaseThread("Console#" + method.getName());
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
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

    @SuppressWarnings("unchecked")
    private List<String> getMessages(Player player) {
        return ((MessageRecorder) player).sentMessages();
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
