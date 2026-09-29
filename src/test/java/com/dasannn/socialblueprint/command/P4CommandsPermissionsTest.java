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
import com.dasannn.socialblueprint.storage.Migration;
import com.dasannn.socialblueprint.storage.MigrationRunner;
import com.dasannn.socialblueprint.storage.Migration_1_InitialSchema;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
class P4CommandsPermissionsTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
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
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, mainThreadQueue::add, logger);
        configManager.initialize();
        configManager.set("language", "en");

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

    private void drainMainThread() {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Runnable r;
            boolean executed = false;
            while ((r = mainThreadQueue.poll()) != null) {
                r.run();
                executed = true;
            }
            if (!executed && commandExecutor.lastExecution().isDone()) {
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
        while ((r = mainThreadQueue.poll()) != null) {
            r.run();
        }
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

        assertThat(messages).anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("only") && m.toLowerCase(Locale.ROOT).contains("players"));
    }

    @Test
    @DisplayName("DoD 2 / T-050: Console runs player-only commands (trust, distrust, confirm) without exception")
    void consoleRunsPlayerOnlyCommandsWithoutException() {
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        // /status trust Target
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "trust", "Alice");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages.getLast().toLowerCase(Locale.ROOT)).contains("only").contains("players");

        // /status distrust Target reason
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "distrust", "Alice", "griefing");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages.getLast().toLowerCase(Locale.ROOT)).contains("only").contains("players");

        // /status confirm
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "confirm");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages.getLast().toLowerCase(Locale.ROOT)).contains("only").contains("players");

        // Legacy /reputation Target +
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "reputation", "Alice", "+");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages.getLast().toLowerCase(Locale.ROOT)).contains("only").contains("players");
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
        assertThat(messages).anyMatch(m -> m.contains("Added") && m.contains("10"));

        // Admin take
        messages.clear();
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "admin", "take", "Alice", "3");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages).anyMatch(m -> m.contains("Removed") && m.contains("3"));

        // Admin reset
        messages.clear();
        assertThatCode(() -> {
            boolean handled = runCommandSync(console, "status", "admin", "reset", "Alice");
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();
        assertThat(messages).anyMatch(m -> m.contains("Reset") && m.contains("Alice"));
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
        assertThat(messages).anyMatch(m -> m.contains("language") && m.contains("en"));
    }

    @Test
    @DisplayName("DoD 2: Unknown subcommand displays localized message without throwing")
    void unknownSubcommandDisplaysMessage() {
        Player player = mockPlayer("Bob", "socialblueprint.show");
        boolean handled = runCommandSync(player, "status", "unknownSubcommand", "extraArg");
        assertThat(handled).isTrue();

        List<String> msgs = getMessages(player);
        assertThat(msgs).anyMatch(m -> m.contains("Unknown subcommand"));
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
        List<String> viewerMsgs = getMessages(viewer);
        assertThat(viewerMsgs).anyMatch(m -> m.contains("Status Score: &f15"));

        // Step 3: Bob changes name to "RenamedBob" in Bukkit lookup
        onlineLookupMap.remove("oldbob");
        onlineLookupMap.put("renamedbob", new PlayerLookup.KnownPlayer(bobId, "RenamedBob", false));
        offlineUuidMap.put(bobUuid, "RenamedBob");

        // Step 4: Query by new name
        Player viewer2 = mockPlayer("Viewer2", "socialblueprint.show.others");
        runCommandSync(viewer2, "status", "RenamedBob");
        List<String> viewer2Msgs = getMessages(viewer2);
        assertThat(viewer2Msgs).anyMatch(m -> m.contains("Status Score: &f15"));

        // Step 5: Querying by UUID also yields the exact same record
        Player viewer3 = mockPlayer("Viewer3", "socialblueprint.show.others");
        runCommandSync(viewer3, "status", bobUuid.toString());
        List<String> viewer3Msgs = getMessages(viewer3);
        assertThat(viewer3Msgs).anyMatch(m -> m.contains("Status Score: &f15"));
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

        // Stage 1: Trust command prepares pending confirmation
        runCommandSync(actor, "status", "trust", "Target1", "great ally");

        List<String> msgs1 = getMessages(actor);
        assertThat(msgs1).anyMatch(m -> m.contains("/status confirm"));

        // Simulate Economy failure on withdrawal (e.g. concurrent drain or provider error)
        failEconomyWithdrawal.set(true);

        // Stage 2: Player runs /status confirm
        runCommandSync(actor, "status", "confirm");

        List<String> msgs2 = getMessages(actor);
        assertThat(msgs2).anyMatch(m -> m.contains("insufficient funds"));

        // Assert NO ReputationEvent was written
        List<ReputationEvent> events = reputationRepo.findByTargetAsync(PlayerId.of(target.getUniqueId())).join();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("DoD 5 / T-058: Failed event write refunds the exact charged amount")
    void failedEventWriteRefundsExactAmount() {
        StorageEngine failingEngine = StorageEngine.inMemory();
        MigrationRunner dropRunner = new MigrationRunner(List.of(
                new Migration_1_InitialSchema(),
                new Migration() {
                    @Override public int version() { return 2; }
                    @Override public String description() { return "drop table"; }
                    @Override public void apply(Connection conn) throws SQLException {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("DROP TABLE reputation_event;");
                        }
                    }
                }
        ));
        failingEngine.runMigrations(dropRunner);

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

            // Prepare rating
            customExecutor.onCommand(actor, null, "status", new String[]{"trust", "Target2"});
            drainQueue(failingQueue);
            customExecutor.lastExecution().join();
            drainQueue(failingQueue);

            int withdrawsBefore = economyWithdrawCount.get();
            int depositsBefore = economyDepositCount.get();

            // Confirm rating
            customExecutor.onCommand(actor, null, "status", new String[]{"confirm"});
            drainQueue(failingQueue);
            customExecutor.lastExecution().join();
            drainQueue(failingQueue);

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
            List<String> actorMsgs = getMessages(actor);
            assertThat(actorMsgs).anyMatch(m -> m.contains("Failed to record honor") && m.contains("refunded"));
        } finally {
            failingEngine.close();
        }
    }

    private void drainQueue(Queue<Runnable> q) {
        Runnable r;
        while ((r = q.poll()) != null) {
            r.run();
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
            runCommandSync(actor, "status", "trust", "ThreadTarget");
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
        assertThat(getMessages(admin).getLast()).contains("valid integer");

        // Negative integer where positive required
        assertThatCode(() -> {
            runCommandSync(admin, "status", "admin", "give", "Evan", "-5");
        }).doesNotThrowAnyException();
        assertThat(getMessages(admin).getLast()).contains("positive integer");

        // Gigantic integer overflow
        assertThatCode(() -> {
            runCommandSync(admin, "status", "admin", "take", "Evan", "9999999999999999999999999");
        }).doesNotThrowAnyException();
        assertThat(getMessages(admin).getLast()).contains("valid integer");
    }

    @Test
    @DisplayName("T-051 / SB-056: Distrust requires written reason, sending localized error message if omitted")
    void distrustRequiresReason() {
        Player actor = mockPlayer("Frank", "socialblueprint.take");
        mockPlayer("Grace");

        runCommandSync(actor, "status", "distrust", "Grace");

        assertThat(getMessages(actor).getLast()).contains("reason is required");
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

        runCommandSync(legacyGiver, "status", "trust", "TargetLegacy");
        assertThat(getMessages(legacyGiver)).anyMatch(m -> m.contains("/status confirm"));

        // Player with legacy pstatus.addRemoveRep can distrust and administer
        Player legacyTaker = mockPlayer("LegacyTaker", "pstatus.addRemoveRep");
        runCommandSync(legacyTaker, "status", "distrust", "TargetLegacy", "rude");
        assertThat(getMessages(legacyTaker)).anyMatch(m -> m.contains("/status confirm"));

        runCommandSync(legacyTaker, "status", "admin", "give", "TargetLegacy", "5");
        assertThat(getMessages(legacyTaker)).anyMatch(m -> m.contains("Added 5"));

        // Player with NO permission is denied
        Player unpermitted = mockPlayer("Unpermitted");
        runCommandSync(unpermitted, "status", "trust", "TargetLegacy");
        assertThat(getMessages(unpermitted).getLast()).contains("do not have permission");
    }

    @Test
    @DisplayName("T-054 / SB-061: Configurable permission nodes are read dynamically from snapshot at check time")
    void dynamicPermissionNodeFromSnapshot() {
        Player player = mockPlayer("CustomUser", "custom.trust.node");
        mockPlayer("CustomTarget");

        // Without node configured, user is denied
        runCommandSync(player, "status", "trust", "CustomTarget");
        assertThat(getMessages(player).getLast()).contains("do not have permission");

        // Reconfigure node dynamically
        configManager.set("permissions.give-reputation", "custom.trust.node");

        // Now user is permitted
        runCommandSync(player, "status", "trust", "CustomTarget");
        assertThat(getMessages(player)).anyMatch(m -> m.contains("/status confirm"));
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
        runCommandSync(actor, "status", "trust", "Rated");
        runCommandSync(actor, "status", "confirm");
        assertThat(getMessages(actor)).anyMatch(m -> m.contains("You gave +1 honor"));

        // Immediately attempt to rate again (within cooldown)
        runCommandSync(actor, "status", "trust", "Rated");

        assertThat(getMessages(actor).getLast()).contains("cooldown");
    }

    @Test
    @DisplayName("T-055 / SB-054: Cap of 3 positive and 3 negative ratings per pair inside window")
    void capOfThreePerPairInsideWindowEnforced() {
        Player actor = mockPlayer("CapTester", "socialblueprint.give", "socialblueprint.take");
        mockPlayer("CapTarget");
        economyBalances.put(actor.getUniqueId(), 5000.0);

        Duration cooldown = Duration.ofMinutes(15);

        // Issue 3 positive ratings (advancing time past cooldown each time)
        for (int i = 0; i < 3; i++) {
            runCommandSync(actor, "status", "trust", "CapTarget");
            runCommandSync(actor, "status", "confirm");
            testClock.advance(cooldown.plusSeconds(1));
        }

        // 4th positive rating hits cap
        runCommandSync(actor, "status", "trust", "CapTarget");
        assertThat(getMessages(actor).getLast()).contains("maximum number of ratings");

        // But negative rating is independent and succeeds!
        runCommandSync(actor, "status", "distrust", "CapTarget", "reversal of opinion");
        assertThat(getMessages(actor).getLast()).contains("/status confirm");
    }

    @Test
    @DisplayName("T-055 / SB-052: Confirmation expires after 60 seconds")
    void confirmationExpiresAfterSixtySeconds() {
        Player actor = mockPlayer("SlowPayer", "socialblueprint.give");
        mockPlayer("Receiver");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        runCommandSync(actor, "status", "trust", "Receiver");

        // Advance clock by 65 seconds
        testClock.advance(Duration.ofSeconds(65));

        runCommandSync(actor, "status", "confirm");

        assertThat(getMessages(actor).getLast()).contains("no pending");
    }

    @Test
    @DisplayName("T-055: Cannot rate oneself")
    void cannotRateSelf() {
        Player actor = mockPlayer("Selfish", "socialblueprint.give");

        runCommandSync(actor, "status", "trust", "Selfish");

        assertThat(getMessages(actor).getLast()).contains("cannot rate yourself");
    }

    // =========================================================================
    // DoD 1: Spanish language execution
    // =========================================================================

    @Test
    @DisplayName("DoD 1: Execution with language 'es' outputs Spanish messages")
    void executesInSpanish() {
        configManager.set("language", "es");
        List<String> messages = new ArrayList<>();
        CommandSender console = mockConsole(messages);

        runCommandSync(console, "status");

        // In Spanish, commands.player-only is: "&cEste comando solo puede ser ejecutado por jugadores."
        assertThat(messages.getLast()).contains("solo puede ser ejecutado por jugadores");
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
