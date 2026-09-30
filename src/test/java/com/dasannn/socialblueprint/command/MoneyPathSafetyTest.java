package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.command.P4CommandsPermissionsTest.RecordingMessageRegistry;
import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.CompensationRecord;
import com.dasannn.socialblueprint.domain.CompensationState;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import com.dasannn.socialblueprint.storage.StorageTestSupport;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verification test suite for P4 money path safety and edge case guarantees:
 * 1. Intent written before withdrawal: crash/failure before withdrawal charges nothing; crash before charge discards.
 * 2. Idempotent refunding: concurrent reconciliation and failed deletes do not double-refund.
 * 3. Withdrawal amount checked against preview: partial/mismatched debits are resolved and refunded.
 * 4. Resetting at Integer.MIN_VALUE: overflow handled with two compensating events summing to +2,147,483,648.
 * 5. Configuration upgrade migration: legacy honor.window migrated to cap-window and default multiplier-window.
 * 6. Config edit audit: confirmed before reporting success; failure reported plainly without rollback.
 */
public class MoneyPathSafetyTest {

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private StorageEngine storage;
    private ReputationRepository reputationRepo;
    private AuditRepository auditRepo;
    private CompensationRepository compensationRepo;
    private ProfileService profileService;
    private HonorService honorService;
    private StatusCommandExecutor commandExecutor;
    private Economy mockEconomy;
    private TestClock testClock;

    private final Queue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Double> economyBalances = new ConcurrentHashMap<>();
    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> offlineUuidMap = new ConcurrentHashMap<>();
    private final List<Player> onlinePlayersList = new ArrayList<>();

    private final AtomicBoolean failEconomyWithdrawal = new AtomicBoolean(false);
    private final AtomicBoolean failEconomyDeposit = new AtomicBoolean(false);
    private final AtomicInteger economyWithdrawCount = new AtomicInteger(0);
    private final AtomicInteger economyDepositCount = new AtomicInteger(0);
    private final AtomicReference<Double> customWithdrawAmount = new AtomicReference<>(null);

    static class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant initial) {
            this.instant = initial;
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

        Logger logger = Logger.getLogger("MoneySafetyTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "es", logger);
        configManager = new ConfigManager(configFile, messageRegistry, mainThreadQueue::add, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        auditRepo = new AuditRepository(storage);
        compensationRepo = new CompensationRepository(storage);

        testClock = new TestClock(Instant.parse("2026-09-30T12:00:00Z"));

        PlayerLookup testLookup = nameOrUuid -> {
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
                new PsychosisRepository(storage),
                new ProfileRepository(storage),
                statusCache,
                configManager,
                testLookup,
                logger
        );

        InvocationHandler econHandler = (proxy, method, args) -> {
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
                if (failEconomyWithdrawal.get()) {
                    return new EconomyResponse(0, economyBalances.getOrDefault(p.getUniqueId(), 1000.0),
                            EconomyResponse.ResponseType.FAILURE, "Simulated withdrawal failure");
                }
                double debitAmount = customWithdrawAmount.get() != null ? customWithdrawAmount.get() : amt;
                double bal = economyBalances.getOrDefault(p.getUniqueId(), 1000.0) - debitAmount;
                economyBalances.put(p.getUniqueId(), bal);
                return new EconomyResponse(debitAmount, bal, EconomyResponse.ResponseType.SUCCESS, null);
            }
            if ("depositPlayer".equals(mName) && args.length >= 2) {
                economyDepositCount.incrementAndGet();
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                if (failEconomyDeposit.get()) {
                    return new EconomyResponse(0, economyBalances.getOrDefault(p.getUniqueId(), 1000.0),
                            EconomyResponse.ResponseType.FAILURE, "Simulated deposit failure");
                }
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
                compensationRepo,
                profileService,
                mockEconomy,
                mainThreadQueue::add,
                testClock,
                uuid -> {
                    String name = offlineUuidMap.getOrDefault(uuid, uuid.toString());
                    return mockPlayer(name);
                }
        );

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profileService,
                honorService,
                auditRepo,
                mainThreadQueue::add,
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
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Runnable r;
            boolean executed = false;
            while ((r = mainThreadQueue.poll()) != null) {
                r.run();
                executed = true;
            }
            CompletableFuture<?> last = commandExecutor.lastExecution();
            if (!executed && (last == null || last.isDone())) {
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
        if (commandExecutor.lastExecution() != null) {
            commandExecutor.lastExecution().join();
        }
        drainMainThread();
        return result;
    }

    private Player mockPlayer(String name, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        List<String> messages = new ArrayList<>();
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("isOnline".equals(mName)) return true;
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
                new Class<?>[]{Player.class, P4CommandsPermissionsTest.MessageRecorder.class},
                handler
        );

        onlineLookupMap.put(name.toLowerCase(Locale.ROOT), new PlayerLookup.KnownPlayer(PlayerId.of(uuid), name, true));
        offlineUuidMap.put(uuid, name);
        onlinePlayersList.add(player);
        return player;
    }

    private CommandSender mockConsole(List<String> messages) {
        InvocationHandler handler = (proxy, method, args) -> {
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

    // =========================================================================
    // 1. Intent written before withdrawal
    // =========================================================================

    @Test
    @DisplayName("Blocking 1: Failed intent insert prevents withdrawal and protects player money")
    void failedIntentInsertPreventsWithdrawal() {
        Player actor = mockPlayer("IntentActor", "socialblueprint.give");
        mockPlayer("IntentTarget");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        runCommandSync(actor, "status", "give", "IntentTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        // Inject DB failure on pending_compensation insert
        StorageTestSupport.setFailCompensationInsertTrigger(storage);

        try {
            runCommandSync(actor, "status", "confirm");

            // Vault withdraw was NEVER executed because intent persistence failed
            assertThat(economyWithdrawCount.get()).isEqualTo(0);
            assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
            assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.write-failed");
        } finally {
            StorageTestSupport.dropFailCompensationInsertTrigger(storage);
        }
    }

    @Test
    @DisplayName("Blocking 1: Server crash after intent write discards row on restart without refunding")
    void crashAfterIntentDiscardedOnReconcile() {
        Player actor = mockPlayer("IntentCrashActor");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        // Record exists in INTENDED state (money was never charged before the crash)
        long id = compensationRepo.saveCompensationAsync(actor.getUniqueId(), 500.0, "honor_charge",
                CompensationState.INTENDED, testClock.instant()).join();

        assertThat(compensationRepo.findByIdAsync(id).join()).isPresent();

        // Server restarts; reconciliation runs
        honorService.reconcileCompensationsAsync().join();
        drainMainThread();

        // Row was discarded
        assertThat(compensationRepo.findByIdAsync(id).join()).isEmpty();
        // No refund deposit was made (player was never charged)
        assertThat(economyDepositCount.get()).isEqualTo(0);
        assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("Blocking 1: Server crash after event write discards row on restart without refunding")
    void crashAfterEventWrittenDiscardedOnReconcile() {
        Player actor = mockPlayer("EventCrashActor");
        economyBalances.put(actor.getUniqueId(), 500.0);

        // Record exists in EVENT_WRITTEN state (event was written, async delete did not complete before crash)
        long id = compensationRepo.saveCompensationAsync(actor.getUniqueId(), 500.0, "honor_charge",
                CompensationState.EVENT_WRITTEN, testClock.instant()).join();

        honorService.reconcileCompensationsAsync().join();
        drainMainThread();

        // Row was deleted
        assertThat(compensationRepo.findByIdAsync(id).join()).isEmpty();
        // No refund deposit was made
        assertThat(economyDepositCount.get()).isEqualTo(0);
        assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(500.0);
    }

    // =========================================================================
    // 2. Idempotent refunding and concurrent reconciliation
    // =========================================================================

    @Test
    @DisplayName("Blocking 2: Failed compensation delete does not cause double refund on subsequent reconciliation")
    void failedCompensationDeleteDoesNotCauseDoubleRefund() {
        Player actor = mockPlayer("DeleteFailActor", "socialblueprint.give");
        Player target = mockPlayer("DeleteFailTarget");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        runCommandSync(actor, "status", "give", "DeleteFailTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        // Fail event insert so immediate refund fires
        StorageTestSupport.setFailReputationTrigger(storage);
        // Fail compensation delete so row remains in database
        StorageTestSupport.setFailCompensationDeleteTrigger(storage);

        try {
            runCommandSync(actor, "status", "confirm");

            // Player was charged 500 and immediately refunded 500 -> ends at 1000.0
            assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
            assertThat(economyDepositCount.get()).isEqualTo(1);

            // Row could not be deleted, but was marked REFUNDED!
            var records = compensationRepo.findAllAsync().join();
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().state()).isEqualTo(CompensationState.REFUNDED);

            // Permit deletes now
            StorageTestSupport.dropFailCompensationDeleteTrigger(storage);

            // Second reconciliation pass runs (e.g. server restart)
            honorService.reconcileCompensationsAsync().join();
            drainMainThread();

            // Player was NOT refunded a second time!
            assertThat(economyDepositCount.get()).isEqualTo(1);
            assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
            // And now row is cleaned up
            assertThat(compensationRepo.findAllAsync().join()).isEmpty();
        } finally {
            StorageTestSupport.dropFailReputationTrigger(storage);
            StorageTestSupport.dropFailCompensationDeleteTrigger(storage);
        }
    }

    @Test
    @DisplayName("Blocking 2: Two concurrent reconciliations claim row before acting and do not double-refund")
    void twoConcurrentReconciliationsDoNotDoubleRefund() {
        Player actor = mockPlayer("ConcurrentActor");
        economyBalances.put(actor.getUniqueId(), 500.0);

        // Charged row left in database
        long id = compensationRepo.saveCompensationAsync(actor.getUniqueId(), 500.0, "honor_charge",
                CompensationState.CHARGED, testClock.instant()).join();

        // Fire two concurrent reconciliation passes
        CompletableFuture<Void> r1 = honorService.reconcileCompensationsAsync();
        CompletableFuture<Void> r2 = honorService.reconcileCompensationsAsync();
        CompletableFuture.allOf(r1, r2).join();
        drainMainThread();

        // Player was refunded exactly ONCE
        assertThat(economyDepositCount.get()).isEqualTo(1);
        assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
        assertThat(compensationRepo.findByIdAsync(id).join()).isEmpty();
    }

    @Test
    @DisplayName("Blocking 2: Server restart with in-flight REFUNDING row leaves UNCERTAIN state without guessing")
    void midDepositCrashLeavesUncertainRow() {
        Player actor = mockPlayer("UncertainActor");
        economyBalances.put(actor.getUniqueId(), 500.0);

        // Row was in REFUNDING state when crash happened
        long id = compensationRepo.saveCompensationAsync(actor.getUniqueId(), 500.0, "honor_charge",
                CompensationState.REFUNDING, testClock.instant()).join();

        honorService.reconcileCompensationsAsync().join();
        drainMainThread();

        // Outcome was unknown; row is left in UNCERTAIN state for operator review
        var record = compensationRepo.findByIdAsync(id).join();
        assertThat(record).isPresent();
        assertThat(record.get().state()).isEqualTo(CompensationState.UNCERTAIN);
        // Did not guess or deposit money
        assertThat(economyDepositCount.get()).isEqualTo(0);
        assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(500.0);
    }

    // =========================================================================
    // 3. Exact preview check
    // =========================================================================

    @Test
    @DisplayName("Blocking 3: Withdrawal amount mismatch against preview is treated as failure and refunded")
    void withdrawalAmountMismatchRefundsAndReportsFailure() {
        Player actor = mockPlayer("MismatchActor", "socialblueprint.give");
        Player target = mockPlayer("MismatchTarget");
        economyBalances.put(actor.getUniqueId(), 1000.0);

        runCommandSync(actor, "status", "give", "MismatchTarget");
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.cost-preview");

        // Simulate Vault debiting 250.0 instead of previewed 500.0
        customWithdrawAmount.set(250.0);

        runCommandSync(actor, "status", "confirm");

        // Reputation event was NOT written
        List<ReputationEvent> events = reputationRepo.findByTargetAsync(PlayerId.of(target.getUniqueId())).join();
        assertThat(events).isEmpty();

        // Partial debit of 250.0 was immediately refunded
        assertThat(economyBalances.get(actor.getUniqueId())).isEqualTo(1000.0);
        // Player notified of failure
        assertThat(messageRegistry.lastCall().key()).isEqualTo("honor.write-failed");
    }

    // =========================================================================
    // 4. Resetting player at Integer.MIN_VALUE
    // =========================================================================

    @Test
    @DisplayName("Blocking 4: Resetting player at Integer.MIN_VALUE compensates with two events summing to 2,147,483,648")
    void resetPlayerAtIntegerMinValueCompensatesWithTwoEvents() {
        Player target = mockPlayer("MinValTarget");
        PlayerId targetId = PlayerId.of(target.getUniqueId());

        // Target status is Integer.MIN_VALUE (-2,147,483,648)
        // Insert events totalling Integer.MIN_VALUE
        Instant now = testClock.instant();
        reputationRepo.saveAsync(new ReputationEvent(PlayerId.CONSOLE, targetId, -1_073_741_824, HonorKind.ADMIN_TAKE, 0.0, "part1", now)).join();
        reputationRepo.saveAsync(new ReputationEvent(PlayerId.CONSOLE, targetId, -1_073_741_824, HonorKind.ADMIN_TAKE, 0.0, "part2", now)).join();

        List<ReputationEvent> initialEvents = reputationRepo.findByTargetAsync(targetId).join();
        Status beforeStatus = Status.fromEvents(initialEvents);
        assertThat(beforeStatus.value()).isEqualTo(Integer.MIN_VALUE);

        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        runCommandSync(console, "status", "admin", "reset", "MinValTarget");

        List<ReputationEvent> updatedEvents = reputationRepo.findByTargetAsync(targetId).join();
        // New derived status is cleanly neutral (0)
        Status afterStatus = Status.fromEvents(updatedEvents);
        assertThat(afterStatus.value()).isEqualTo(0);

        // Verification: two compensating events inside transaction: Integer.MAX_VALUE and 1
        List<ReputationEvent> resetEvents = updatedEvents.stream()
                .filter(e -> e.kind() == HonorKind.ADMIN_RESET)
                .toList();
        assertThat(resetEvents).hasSize(2);
        assertThat(resetEvents.get(0).delta()).isEqualTo(Integer.MAX_VALUE);
        assertThat(resetEvents.get(1).delta()).isEqualTo(1);

        // Audit row records before = "-2147483648" and after = "0"
        List<AuditEvent> audits = auditRepo.findByTarget(targetId);
        assertThat(audits).isNotEmpty();
        AuditEvent resetAudit = audits.getLast();
        assertThat(resetAudit.before()).isEqualTo(String.valueOf(Integer.MIN_VALUE));
        assertThat(resetAudit.after()).isEqualTo("0");
    }

    // =========================================================================
    // 5. Config upgrade migration for legacy honor.window
    // =========================================================================

    @Test
    @DisplayName("Blocking 5: Legacy honor.window key is migrated to cap-window and multiplier-window on load")
    void legacyHonorWindowMigratedSuccessfully() throws Exception {
        File upgradeConfigFile = new File(tempDir, "legacy_config.yml");
        String legacyYaml = """
            language: es
            chat-prefix: '&8[&bSocialBlueprint&8]&r '
            tiers:
              tier-4: { prefix: '&7[&4||||&7]', threshold: -30 }
              tier-3: { prefix: '&7[&c|||&7]', threshold: -20 }
              tier-2: { prefix: '&7[&c||&7]', threshold: -10 }
              tier-1: { prefix: '&7[&c|&7]', threshold: -1 }
              tier0: { prefix: '&7[&f|&7]', threshold: 0 }
              tier1: { prefix: '&7[&a|&7]', threshold: 5 }
              tier2: { prefix: '&7[&a||&7]', threshold: 15 }
              tier3: { prefix: '&7[&a|||&7]', threshold: 30 }
              tier4: { prefix: '&7[&b||||&7]', threshold: 50 }
            confidence:
              half-life: 30d
              low-threshold: 1.0
              established-threshold: 5.0
              high-threshold: 15.0
            psychosis:
              window: 24h
              medium-threshold: 2
              high-threshold: 5
              extreme-threshold: 10
            honor:
              cost: 500.0
              multipliers:
                - 1.0
                - 1.5
                - 2.0
                - 3.0
              window: 7d
              cooldown-per-pair: 24h
              max-per-target: 3
            permissions:
              show: "socialblueprint.show"
              show-others: "socialblueprint.show.others"
              give-reputation: "socialblueprint.give"
              take-reputation: "socialblueprint.take"
              view-reputation: "socialblueprint.view"
              admin-adjust: "socialblueprint.admin.adjust"
              admin-config: "socialblueprint.admin.config"
            """;
        Files.writeString(upgradeConfigFile.toPath(), legacyYaml, StandardCharsets.UTF_8);

        ConfigManager upgradeManager = new ConfigManager(upgradeConfigFile, messageRegistry, Runnable::run, Logger.getLogger("UpgradeTest"));

        // Boot should succeed without ConfigValidationException
        assertThatCode(upgradeManager::initialize).doesNotThrowAnyException();

        // honor.window value adopted as cap-window (7d)
        assertThat(upgradeManager.config().honor().capWindow()).isEqualTo(Duration.ofDays(7));
        // multiplier-window set to default (1h)
        assertThat(upgradeManager.config().honor().multiplierWindow()).isEqualTo(Duration.ofHours(1));

        // File on disk was rewritten
        String diskContent = Files.readString(upgradeConfigFile.toPath(), StandardCharsets.UTF_8);
        assertThat(diskContent).contains("multiplier-window: 1h");
        assertThat(diskContent).contains("cap-window: 7d");
    }

    // =========================================================================
    // 6. Config edit audit confirmation before success report
    // =========================================================================

    @Test
    @DisplayName("Fix 6: Config edit audit failure reports failure and logs error without rolling back config")
    void configEditAuditFailureReportsFailureWithoutRollback() {
        Player admin = mockPlayer("AuditAdmin", "socialblueprint.admin.config");

        // Fail audit_event insert
        StorageTestSupport.setFailAuditTrigger(storage);

        try {
            runCommandSync(admin, "status", "config", "set", "honor.cost", "750.0");

            // Config was updated (owner requested it; no rollback)
            assertThat(configManager.get("honor.cost")).isEqualTo("750.0");

            // Sender did NOT receive set-success
            assertThat(messageRegistry.renderedCalls().stream().noneMatch(c -> "commands.config.set-success".equals(c.key()))).isTrue();

            // Sender received failure message plainly stating audit failed
            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.config.set-failed");
            assertThat(messageRegistry.lastCall().placeholders().get("error")).contains("audit");
        } finally {
            StorageTestSupport.dropFailAuditTrigger(storage);
        }
    }
}
