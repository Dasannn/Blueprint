package com.dasannn.socialblueprint.feature.legacy;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.command.StatusCommandExecutor;
import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.LegacyImportConfig;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.ConfidenceCalculator;
import com.dasannn.socialblueprint.domain.ConfidenceConfig;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
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
import java.time.Duration;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyImportTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private StatusCache statusCache;
    private ReputationRepository reputationRepo;
    private ProfileRepository profileRepo;
    private PsychosisRepository psychosisRepo;
    private AuditRepository auditRepo;
    private ProfileService profileService;

    private final Map<String, UUID> knownResolutions = new HashMap<>();
    private final AtomicReference<Thread> lastResolutionThread = new AtomicReference<>();
    private final List<String> consoleSentMessages = new ArrayList<>();
    private ConsoleCommandSender consoleSender;

    private LegacyImportService legacyImportService;
    private StatusCommandExecutor commandExecutor;

    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("LegacyImportTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "es", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        profileRepo = new ProfileRepository(storage);
        psychosisRepo = new PsychosisRepository(storage);
        auditRepo = new AuditRepository(storage);

        PlayerLookup testLookup = nameOrUuid -> {
            lastResolutionThread.set(Thread.currentThread());
            if (knownResolutions.containsKey(nameOrUuid.toLowerCase())) {
                UUID uuid = knownResolutions.get(nameOrUuid.toLowerCase());
                return Optional.of(new PlayerLookup.KnownPlayer(PlayerId.of(uuid), nameOrUuid, true));
            }
            return Optional.empty();
        };

        consoleSender = mockConsole(consoleSentMessages);

        legacyImportService = new LegacyImportService(
                storage,
                reputationRepo,
                profileRepo,
                auditRepo,
                messageRegistry,
                testLookup,
                tempDir,
                Runnable::run,
                () -> consoleSender,
                logger
        );

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
                auditRepo,
                legacyImportService,
                Runnable::run,
                Collections::emptyList
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-090: Reads old config with authoritative UUID rows, writes exactly one ReputationEvent per player")
    void readOldConfig_writesExactReputationEventPerPlayer() throws Exception {
        UUID uuidSteve = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID uuidAlex = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID uuidDirect = UUID.fromString("33333333-3333-3333-3333-333333333333");

        // Baseline PlayerStatus format keys playerList by UUID
        String oldYaml = """
            playerList:
              11111111-1111-1111-1111-111111111111:
                name: 'Steve'
                reputation: 25
              22222222-2222-2222-2222-222222222222:
                name: 'Alex'
                reputation: -10
              33333333-3333-3333-3333-333333333333: 15
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), oldYaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // 1. Verify Steve: keyed by UUID in old config
        List<ReputationEvent> steveEvents = reputationRepo.findByTarget(PlayerId.of(uuidSteve));
        assertThat(steveEvents).hasSize(1);
        ReputationEvent steveEvt = steveEvents.getFirst();
        assertThat(steveEvt.actor()).isNull();
        assertThat(steveEvt.kind()).isEqualTo(HonorKind.LEGACY_IMPORT);
        assertThat(steveEvt.delta()).isEqualTo(25);
        assertThat(steveEvt.cost()).isEqualTo(0.0);
        assertThat(steveEvt.reason()).isEqualTo("commands.admin.import.reason");

        Optional<PlayerProfile> steveProfile = profileRepo.findById(PlayerId.of(uuidSteve));
        assertThat(steveProfile).isPresent();
        assertThat(steveProfile.get().lastKnownName()).isEqualTo("Steve");

        // 2. Verify Alex: keyed by UUID in old config
        List<ReputationEvent> alexEvents = reputationRepo.findByTarget(PlayerId.of(uuidAlex));
        assertThat(alexEvents).hasSize(1);
        ReputationEvent alexEvt = alexEvents.getFirst();
        assertThat(alexEvt.actor()).isNull();
        assertThat(alexEvt.kind()).isEqualTo(HonorKind.LEGACY_IMPORT);
        assertThat(alexEvt.delta()).isEqualTo(-10);
        assertThat(alexEvt.cost()).isEqualTo(0.0);
        assertThat(alexEvt.reason()).isEqualTo("commands.admin.import.reason");

        Optional<PlayerProfile> alexProfile = profileRepo.findById(PlayerId.of(uuidAlex));
        assertThat(alexProfile).isPresent();
        assertThat(alexProfile.get().lastKnownName()).isEqualTo("Alex");

        // 3. Verify Direct: scalar score keyed by UUID
        List<ReputationEvent> directEvents = reputationRepo.findByTarget(PlayerId.of(uuidDirect));
        assertThat(directEvents).hasSize(1);
        ReputationEvent directEvt = directEvents.getFirst();
        assertThat(directEvt.actor()).isNull();
        assertThat(directEvt.kind()).isEqualTo(HonorKind.LEGACY_IMPORT);
        assertThat(directEvt.delta()).isEqualTo(15);
        assertThat(directEvt.cost()).isEqualTo(0.0);
        assertThat(directEvt.reason()).isEqualTo("commands.admin.import.reason");
    }

    @Test
    @DisplayName("T-090 / SB-060: Unresolvable player name is skipped and reported, never guessed")
    void unresolvablePlayerName_isSkippedAndReported_neverGuessed() throws Exception {
        UUID uuidAlice = UUID.fromString("44444444-4444-4444-4444-444444444444");
        knownResolutions.put("alice", uuidAlice);
        // GhostPlayer is intentionally NOT in knownResolutions

        String oldYaml = """
            playerList:
              Alice:
                name: 'Alice'
                reputation: 10
              GhostPlayer:
                name: 'GhostPlayer'
                reputation: 50
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), oldYaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        RuntimeSnapshot trustedSnapshot = new RuntimeSnapshot(
                configManager.config().withLegacyImport(new LegacyImportConfig(true)),
                configManager.snapshot().messages()
        );

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), trustedSnapshot).join();

        // Alice is imported
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlice))).hasSize(1);

        // GhostPlayer resolved to no UUID, so nothing can have been written against one
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlice)))
                .singleElement()
                .satisfies(event -> assertThat(event.delta()).isNotEqualTo(50));

        // Reports skipped to sender and console
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-unresolved", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders()).containsExactlyInAnyOrderEntriesOf(Map.of("player", "GhostPlayer"));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "2", "imported", "1", "skipped", "1"));
        assertThat(senderMessages).containsExactlyElementsOf(messageRegistry.renderedMessages());
        assertThat(consoleSentMessages).containsExactlyElementsOf(messageRegistry.renderedMessages());
    }

    @Test
    @DisplayName("T-091 / SB-003: LEGACY_IMPORT events contribute zero to Reputation Confidence")
    void legacyEventsContributeNothingToReputationConfidence() {
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // Event with huge positive score (+80) but kind = LEGACY_IMPORT and actor = null
        ReputationEvent legacyEvent = new ReputationEvent(
                0L,
                null,
                target,
                80,
                HonorKind.LEGACY_IMPORT,
                0.0,
                "commands.admin.import.reason",
                baseTime
        );

        // Calculate confidence
        ConfidenceCalculator calculator = new ConfidenceCalculator(
                new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30)));

        // Confidence must be UNKNOWN: no distinct actor, no weight
        assertThat(calculator.calculate(List.of(legacyEvent), baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(calculator.countDistinctActors(List.of(legacyEvent))).isEqualTo(0);
        assertThat(calculator.calculateScore(List.of(legacyEvent), baseTime)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("T-091: Subsequent imports skip already-imported players (idempotence) with score report")
    void idempotentImportSkipsExistingTargets() throws Exception {
        UUID uuidDave = UUID.fromString("66666666-6666-6666-6666-666666666666");

        String yaml = """
            playerList:
              66666666-6666-6666-6666-666666666666:
                name: 'Dave'
                reputation: 35
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages1 = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages1, "socialblueprint.admin");

        // Run 1: Successful import
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.summary");
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "1", "imported", "1", "skipped", "0"));
        assertThat(senderMessages1).containsExactlyElementsOf(messageRegistry.renderedMessages());

        Status statusAfterFirst = reputationRepo.getStatus(PlayerId.of(uuidDave));
        assertThat(statusAfterFirst.value()).isEqualTo(35);
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidDave))).hasSize(1);

        // Run 2: Idempotent rerun
        List<String> senderMessages2 = new ArrayList<>();
        Player admin2 = mockPlayer("AdminUser2", senderMessages2, "socialblueprint.admin");
        consoleSentMessages.clear();
        messageRegistry.clear();

        legacyImportService.importLegacyAsync(admin2, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Second run must report 0 imported, 1 skipped as already imported with kept & ignored scores
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-already-imported", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("player", "Dave", "kept", "35", "ignored", "35"));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "1", "imported", "0", "skipped", "1"));
        assertThat(senderMessages2).containsExactlyElementsOf(messageRegistry.renderedMessages());
        assertThat(consoleSentMessages).containsExactlyElementsOf(messageRegistry.renderedMessages());

        // Status score MUST NOT double (remains 35, not 70)
        Status statusAfterSecond = reputationRepo.getStatus(PlayerId.of(uuidDave));
        assertThat(statusAfterSecond.value()).isEqualTo(35);
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidDave))).hasSize(1);
    }

    @Test
    @DisplayName("T-092: Import reports started and summary to both sender and console")
    void importReportsToBothSenderAndConsole() throws Exception {
        UUID uuidEve = UUID.fromString("77777777-7777-7777-7777-777777777777");

        String yaml = """
            playerList:
              77777777-7777-7777-7777-777777777777:
                name: 'Eve'
                reputation: 12
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminEve", senderMessages, "socialblueprint.admin");
        consoleSentMessages.clear();

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Check sender messages
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().getFirst().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("file", legacyFile.getAbsolutePath()));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "1", "imported", "1", "skipped", "0"));
        assertThat(senderMessages).containsExactlyElementsOf(messageRegistry.renderedMessages());

        // Check console messages (dual reporting)
        assertThat(consoleSentMessages).containsExactlyElementsOf(messageRegistry.renderedMessages());
    }

    @Test
    @DisplayName("T-092: Writing audit record on legacy import")
    void importWritesAuditRecord() throws Exception {
        UUID uuidFrank = UUID.fromString("88888888-8888-8888-8888-888888888888");

        String yaml = """
            playerList:
              88888888-8888-8888-8888-888888888888:
                name: 'Frank'
                reputation: 8
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminFrank", new ArrayList<>(), "socialblueprint.admin");
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        List<AuditEvent> audits = auditRepo.findByTarget(com.dasannn.socialblueprint.domain.NonPlayerTarget.of(legacyFile.getName()));
        assertThat(audits).isNotEmpty();
        AuditEvent importAudit = audits.stream()
                .filter(a -> "legacy_import".equals(a.operation()))
                .findFirst()
                .orElse(null);

        assertThat(importAudit).isNotNull();
        assertThat(importAudit.actor()).isEqualTo(PlayerId.of(admin.getUniqueId()));
        assertThat(importAudit.before()).isEqualTo("0");
        assertThat(importAudit.after()).isEqualTo("1");
    }

    @Test
    @DisplayName("Invalid scores and malformed shapes are skipped with INVALID_SCORE")
    void invalidScoreEntriesAreSkipped() throws Exception {
        UUID uuidGrace = UUID.fromString("99999999-9999-9999-9999-999999999999");

        String yaml = """
            playerList:
              99999999-9999-9999-9999-999999999999:
                name: 'Grace'
                reputation: 10
              00000000-0000-0000-0000-000000000001:
                name: 'BadScorePlayer'
                reputation: 'NotANumber'
              00000000-0000-0000-0000-000000000002:
                name: 'ExcessivePlayer'
                reputation: 9999999
            """;
        File legacyFile = new File(tempDir, "legacy_config.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Grace imported
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidGrace))).hasSize(1);

        // Summary reports 3 read, 1 imported, 2 skipped
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-invalid-score",
                        "commands.admin.import.skipped-invalid-score", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("player", "BadScorePlayer", "score", "NotANumber"));
        assertThat(messageRegistry.renderedCalls().get(2).placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("player", "ExcessivePlayer", "score", "Score out of range: 9999999"));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "3", "imported", "1", "skipped", "2"));
        assertThat(senderMessages).hasSize(4);
    }

    @Test
    @DisplayName("Command routing: /status admin import and /status import require admin-import permission")
    void commandRoutingAndPermissions() {
        Player noPerm = mockPlayer("NoPermUser", new ArrayList<>());
        Player hasAdminImport = mockPlayer("ImportAdmin", new ArrayList<>(), "socialblueprint.admin.import");
        Player hasLegacyAdmin = mockPlayer("LegacyAdmin", new ArrayList<>(), "pstatus.admin");

        // 1. No permissions -> denied
        boolean executedNoPerm = commandExecutor.onCommand(noPerm, null, "status", new String[]{"admin", "import"});
        assertThat(executedNoPerm).isTrue();
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
        assertThat(getMessages(noPerm)).hasSize(1);

        boolean executedNoPermDirect = commandExecutor.onCommand(noPerm, null, "status", new String[]{"import"});
        assertThat(executedNoPermDirect).isTrue();
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.no-permission");
        assertThat(getMessages(noPerm)).hasSize(2);

        // 2. Has socialblueprint.admin.import -> permitted (file not found message confirms it entered handler)
        boolean executedAdmin = commandExecutor.onCommand(hasAdminImport, null, "status", new String[]{"admin", "import", "non_existent.yml"});
        assertThat(executedAdmin).isTrue();
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.import.file-not-found");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("file", "non_existent.yml");
        assertThat(getMessages(hasAdminImport)).hasSize(2);

        // 3. Has legacy pstatus.admin -> permitted via /status import
        boolean executedLegacy = commandExecutor.onCommand(hasLegacyAdmin, null, "status", new String[]{"import", "non_existent.yml"});
        assertThat(executedLegacy).isTrue();
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.import.file-not-found");
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("file", "non_existent.yml");
        assertThat(getMessages(hasLegacyAdmin)).hasSize(2);
    }

    @Test
    @DisplayName("Concurrency guard: running second import while one is active returns already-running")
    void concurrencyGuardBlocksOverlappingImports() throws Exception {
        java.util.concurrent.CountDownLatch startedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceedLatch = new java.util.concurrent.CountDownLatch(1);

        PlayerLookup blockingLookup = name -> {
            startedLatch.countDown();
            try {
                proceedLatch.await();
            } catch (InterruptedException ignored) {
            }
            return Optional.empty();
        };

        LegacyImportService blockingService = new LegacyImportService(
                storage,
                reputationRepo,
                profileRepo,
                auditRepo,
                messageRegistry,
                blockingLookup,
                tempDir,
                Runnable::run,
                () -> consoleSender,
                Logger.getLogger("test-blocking")
        );

        String yaml = """
            playerList:
              SlowPlayer:
                reputation: 10
            """;
        File legacyFile = new File(tempDir, "blocking.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> admin1Messages = new ArrayList<>();
        Player admin1 = mockPlayer("Admin1", admin1Messages, "socialblueprint.admin");

        List<String> admin2Messages = new ArrayList<>();
        Player admin2 = mockPlayer("Admin2", admin2Messages, "socialblueprint.admin");

        RuntimeSnapshot trustedSnapshot = new RuntimeSnapshot(
                configManager.config().withLegacyImport(new LegacyImportConfig(true)),
                configManager.snapshot().messages()
        );

        var f1 = CompletableFuture.supplyAsync(
                () -> blockingService.importLegacyAsync(admin1, legacyFile.getAbsolutePath(), trustedSnapshot))
                .thenCompose(importFuture -> importFuture);
        startedLatch.await();

        try {
            var f2 = blockingService.importLegacyAsync(admin2, legacyFile.getAbsolutePath(), trustedSnapshot);
            f2.join();

            assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.import.already-running");
            assertThat(admin2Messages).containsExactly(messageRegistry.lastCall().message());
        } finally {
            proceedLatch.countDown();
        }
        f1.join();
    }

    // =========================================================================
    // Findings 1 - 7 Regression and Specification Tests
    // =========================================================================

    @Test
    @DisplayName("Finding 1: Name-only row is skipped as unverified by default without guessing identity")
    void nameOnlyRow_isSkippedAsUnverified_byDefault() throws Exception {
        UUID uuidAlex = UUID.fromString("22222222-2222-2222-2222-222222222222");
        knownResolutions.put("alex", uuidAlex);

        String yaml = """
            playerList:
              Alex:
                name: 'Alex'
                reputation: 25
            """;
        File legacyFile = new File(tempDir, "name_only_default.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Must NOT be imported to uuidAlex
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlex))).isEmpty();

        // Must report skipped-unverified
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-unverified", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("player", "Alex"));
        assertThat(messageRegistry.lastCall().placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("read", "1", "imported", "0", "skipped", "1"));
    }

    @Test
    @DisplayName("Finding 1: Name-only row imports when trust-name-lookup is true")
    void nameOnlyRow_imports_whenTrustNameLookupIsTrue() throws Exception {
        UUID uuidAlex = UUID.fromString("22222222-2222-2222-2222-222222222222");
        knownResolutions.put("alex", uuidAlex);

        String yaml = """
            playerList:
              Alex:
                name: 'Alex'
                reputation: 25
            """;
        File legacyFile = new File(tempDir, "name_only_trusted.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        RuntimeSnapshot trustedSnapshot = new RuntimeSnapshot(
                configManager.config().withLegacyImport(new LegacyImportConfig(true)),
                configManager.snapshot().messages()
        );

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), trustedSnapshot).join();

        // Must be imported to uuidAlex
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlex))).hasSize(1);
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.summary");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("read", "1", "imported", "1", "skipped", "0"));
    }

    @Test
    @DisplayName("Finding 1: UUID-keyed row imports normally regardless of trust-name-lookup setting")
    void uuidKeyedRow_importsNormally_regardlessOfTrustNameLookup() throws Exception {
        UUID uuidAlex = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String yaml = """
            playerList:
              22222222-2222-2222-2222-222222222222:
                name: 'Alex'
                reputation: 25
            """;
        File legacyFile = new File(tempDir, "uuid_keyed_default.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        List<String> senderMessages = new ArrayList<>();
        Player admin = mockPlayer("AdminUser", senderMessages, "socialblueprint.admin");

        // With default trust-name-lookup = false
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlex))).hasSize(1);
        assertThat(messageRegistry.lastCall().placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("read", "1", "imported", "1", "skipped", "0"));
    }

    @Test
    @DisplayName("Finding 2: Import does not overwrite existing profile state (preserves opt-out and current name)")
    void importDoesNotOverwriteExistingProfile() throws Exception {
        UUID uuidPlayer = UUID.fromString("55555555-5555-5555-5555-555555555555");
        PlayerId targetId = PlayerId.of(uuidPlayer);

        // Pre-existing profile with effectsOptOut = true and a current live name
        PlayerProfile existingProfile = new PlayerProfile(
                targetId,
                "CurrentLiveName",
                true,
                baseTime.minus(Duration.ofDays(30)),
                baseTime.minus(Duration.ofDays(5))
        );
        profileRepo.saveAsync(existingProfile).join();

        // Old config carries stale name "OldStaleName"
        String yaml = """
            playerList:
              55555555-5555-5555-5555-555555555555:
                name: 'OldStaleName'
                reputation: 40
            """;
        File legacyFile = new File(tempDir, "profile_safety.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // 1. Reputation event was created for the UUID
        List<ReputationEvent> events = reputationRepo.findByTarget(targetId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().delta()).isEqualTo(40);

        // 2. Profile MUST NOT have been overwritten: opt-out remains true, name remains CurrentLiveName
        Optional<PlayerProfile> currentProfile = profileRepo.findById(targetId);
        assertThat(currentProfile).isPresent();
        assertThat(currentProfile.get().effectsOptOut()).isTrue();
        assertThat(currentProfile.get().lastKnownName()).isEqualTo("CurrentLiveName");
    }

    @Test
    @DisplayName("Finding 2: Import inserts profile only when absent")
    void importInsertsProfileWhenAbsent() throws Exception {
        UUID uuidNew = UUID.fromString("12341234-1234-1234-1234-123412341234");
        PlayerId targetId = PlayerId.of(uuidNew);

        // Verify profile does not exist initially
        assertThat(profileRepo.findById(targetId)).isEmpty();

        String yaml = """
            playerList:
              12341234-1234-1234-1234-123412341234:
                name: 'NewPlayer'
                reputation: 20
            """;
        File legacyFile = new File(tempDir, "profile_insert.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Profile should now be inserted
        Optional<PlayerProfile> createdProfile = profileRepo.findById(targetId);
        assertThat(createdProfile).isPresent();
        assertThat(createdProfile.get().lastKnownName()).isEqualTo("NewPlayer");
        assertThat(createdProfile.get().effectsOptOut()).isFalse();
    }

    @Test
    @DisplayName("Finding 3: Corrupt YAML, wrong shape, and empty player list report three distinct outcomes")
    void malformedFilesReportThreeDistinctOutcomes() throws Exception {
        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");

        // 1. Invalid YAML syntax
        File corruptFile = new File(tempDir, "corrupt.yml");
        Files.writeString(corruptFile.toPath(), "playerList:\n  unclosed: { missing_bracket", StandardCharsets.UTF_8);

        messageRegistry.clear();
        legacyImportService.importLegacyAsync(admin, corruptFile.getAbsolutePath(), configManager.snapshot()).join();
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.invalid-yaml");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("file", corruptFile.getAbsolutePath());
        assertThat(legacyImportService.isImporting()).isFalse();

        // 2. Wrong shape (root has no playerList/players section)
        File wrongShapeFile = new File(tempDir, "wrong_shape.yml");
        Files.writeString(wrongShapeFile.toPath(), "other_section:\n  foo: bar\n", StandardCharsets.UTF_8);

        messageRegistry.clear();
        legacyImportService.importLegacyAsync(admin, wrongShapeFile.getAbsolutePath(), configManager.snapshot()).join();
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.invalid-shape");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("file", wrongShapeFile.getAbsolutePath());
        assertThat(legacyImportService.isImporting()).isFalse();

        // 3. Valid shape but zero players in playerList
        File emptyPlayersFile = new File(tempDir, "empty_players.yml");
        Files.writeString(emptyPlayersFile.toPath(), "playerList: {}\n", StandardCharsets.UTF_8);

        messageRegistry.clear();
        legacyImportService.importLegacyAsync(admin, emptyPlayersFile.getAbsolutePath(), configManager.snapshot()).join();
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.no-players-found");
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("file", emptyPlayersFile.getAbsolutePath());
        assertThat(legacyImportService.isImporting()).isFalse();
    }

    @Test
    @DisplayName("Finding 4: Bukkit identity lookups run on the calling thread, never on the storage thread")
    void bukkitLookupRunsOnCallingThreadNotStorageThread() throws Exception {
        UUID uuidBob = UUID.fromString("66666666-6666-6666-6666-666666666666");
        knownResolutions.put("bob", uuidBob);

        String yaml = """
            playerList:
              Bob:
                name: 'Bob'
                reputation: 15
            """;
        File legacyFile = new File(tempDir, "thread_test.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");

        RuntimeSnapshot trustedSnapshot = new RuntimeSnapshot(
                configManager.config().withLegacyImport(new LegacyImportConfig(true)),
                configManager.snapshot().messages()
        );

        lastResolutionThread.set(null);
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), trustedSnapshot).join();

        // Resolution MUST have occurred on the calling thread (current test thread), NOT on the storage executor thread
        assertThat(lastResolutionThread.get()).isNotNull();
        assertThat(lastResolutionThread.get()).isEqualTo(Thread.currentThread());
        assertThat(lastResolutionThread.get().getName()).doesNotContain("socialblueprint-db");
    }

    @Test
    @DisplayName("Finding 5: Rerun or conflicting import reports kept vs ignored scores in skip line")
    void secondImportWithDifferentScoreReportsConflictWithKeptAndIgnored() throws Exception {
        UUID uuidDave = UUID.fromString("66666666-6666-6666-6666-666666666666");
        PlayerId targetId = PlayerId.of(uuidDave);

        // File A: Dave has score +10
        String yamlA = """
            playerList:
              66666666-6666-6666-6666-666666666666:
                name: 'Dave'
                reputation: 10
            """;
        File fileA = new File(tempDir, "fileA.yml");
        Files.writeString(fileA.toPath(), yamlA, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
        legacyImportService.importLegacyAsync(admin, fileA.getAbsolutePath(), configManager.snapshot()).join();

        assertThat(reputationRepo.getStatus(targetId).value()).isEqualTo(10);

        // File B: Dave has conflicting score -5
        String yamlB = """
            playerList:
              66666666-6666-6666-6666-666666666666:
                name: 'Dave'
                reputation: -5
            """;
        File fileB = new File(tempDir, "fileB.yml");
        Files.writeString(fileB.toPath(), yamlB, StandardCharsets.UTF_8);

        messageRegistry.clear();
        legacyImportService.importLegacyAsync(admin, fileB.getAbsolutePath(), configManager.snapshot()).join();

        // Must report conflict with kept and ignored
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-already-imported", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("player", "Dave", "kept", "10", "ignored", "-5")
        );

        // Score remains 10 (not updated, not added)
        assertThat(reputationRepo.getStatus(targetId).value()).isEqualTo(10);
        assertThat(reputationRepo.findByTarget(targetId)).hasSize(1);
    }

    @Test
    @DisplayName("Finding 6: in-flight guard is released if setup or submission throws an exception")
    void inFlightGuardReleasedOnSetupFailure() {
        // Create a service pointing to a closed storage engine to cause synchronous throw in supplyAsync
        StorageEngine closedStorage = StorageEngine.inMemory();
        closedStorage.runMigrations();
        closedStorage.close(); // Close storage immediately

        LegacyImportService failingService = new LegacyImportService(
                closedStorage,
                reputationRepo,
                profileRepo,
                auditRepo,
                messageRegistry,
                nameOrUuid -> Optional.empty(),
                tempDir,
                Runnable::run,
                () -> consoleSender,
                Logger.getLogger("test-failing")
        );

        String yaml = """
            playerList:
              11111111-1111-1111-1111-111111111111:
                reputation: 10
            """;
        File legacyFile = new File(tempDir, "throw_test.yml");
        try {
            Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
        failingService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // The guard must NOT stick on true
        assertThat(failingService.isImporting()).isFalse();
    }

    @Test
    @DisplayName("Finding 7: Non-integer scores, overflows, and Integer.MIN_VALUE are rejected as INVALID_SCORE")
    void nonIntegerAndOverflowScoresAreRejectedWithoutRounding() throws Exception {
        String yaml = """
            playerList:
              00000000-0000-0000-0000-000000000001: 3.5
              00000000-0000-0000-0000-000000000002: 3.0
              00000000-0000-0000-0000-000000000003: '99999999999999999999'
              00000000-0000-0000-0000-000000000004: -2147483648
              00000000-0000-0000-0000-000000000005: 10001
              00000000-0000-0000-0000-000000000006: -10001
              00000000-0000-0000-0000-000000000007: 10000
              00000000-0000-0000-0000-000000000008: -10000
            """;
        File legacyFile = new File(tempDir, "scores_strict.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // 6 invalid rows skipped, 2 valid boundary rows (10000, -10000) imported
        assertThat(messageRegistry.lastCall().placeholders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("read", "8", "imported", "2", "skipped", "6"));

        UUID uuidValidPos = UUID.fromString("00000000-0000-0000-0000-000000000007");
        UUID uuidValidNeg = UUID.fromString("00000000-0000-0000-0000-000000000008");
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidValidPos))).hasSize(1);
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidValidNeg))).hasSize(1);
    }

    @Test
    @DisplayName("Finding 4: Legacy import does disk resolution on ioExecutor and player lookups on main thread")
    void finding4_legacyImportUsesIoExecutorAndHopsToMainThreadForParsing() throws Exception {
        String yaml = """
            playerList:
              PlayerOne:
                name: 'PlayerOne'
                reputation: 50
            """;
        File legacyFile = new File(tempDir, "finding4.yml");
        Files.writeString(legacyFile.toPath(), yaml, StandardCharsets.UTF_8);

        AtomicReference<Thread> ioThread = new AtomicReference<>();
        AtomicReference<Thread> mainThread = new AtomicReference<>();
        java.util.concurrent.CountDownLatch hopLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService testIo = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "TestIoThread"));
        java.util.concurrent.ExecutorService testMain = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "TestMainServerThread"));

        Thread callerThread = Thread.currentThread();

        LegacyImportService serviceWithThreadTracking = new LegacyImportService(
                storage,
                reputationRepo,
                profileRepo,
                auditRepo,
                messageRegistry,
                nameOrUuid -> {
                    // Record lookup thread and trip latch
                    mainThread.set(Thread.currentThread());
                    hopLatch.countDown();
                    return Optional.of(new PlayerLookup.KnownPlayer(
                            PlayerId.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                            "PlayerOne",
                            true
                    ));
                },
                tempDir,
                r -> testMain.submit(r),
                () -> consoleSender,
                Logger.getLogger("test"),
                r -> testIo.submit(() -> {
                    ioThread.set(Thread.currentThread());
                    r.run();
                })
        );

        RuntimeSnapshot trustedSnapshot = new RuntimeSnapshot(
                configManager.config().withLegacyImport(new LegacyImportConfig(true)),
                configManager.snapshot().messages()
        );

        try {
            Player admin = mockPlayer("AdminUser", new ArrayList<>(), "socialblueprint.admin");
            CompletableFuture<Void> importFuture = serviceWithThreadTracking.importLegacyAsync(admin, legacyFile.getAbsolutePath(), trustedSnapshot);

            assertThat(hopLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            importFuture.join();

            assertThat(ioThread.get()).isNotNull();
            assertThat(ioThread.get()).isNotEqualTo(callerThread);
            assertThat(mainThread.get()).isNotNull();
            assertThat(mainThread.get().getName()).isEqualTo("TestMainServerThread");
            assertThat(mainThread.get()).isNotEqualTo(ioThread.get());
        } finally {
            testIo.shutdownNow();
            testMain.shutdownNow();
        }
    }

    // Helper methods for dynamic proxies
    private Player mockPlayer(String name, List<String> messageCollector, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
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
                    messageCollector.add(ColorParser.serialize(comp));
                }
                return null;
            }
            if ("sentMessages".equals(mName)) return messageCollector;
            return defaultValue(method.getReturnType());
        };

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, MessageRecorder.class},
                handler
        );
    }

    private ConsoleCommandSender mockConsole(List<String> messageCollector) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messageCollector.add(ColorParser.serialize(comp));
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        };

        return (ConsoleCommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> getMessages(Player player) {
        if (player instanceof MessageRecorder recorder) {
            return recorder.sentMessages();
        }
        return Collections.emptyList();
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }

    private interface MessageRecorder {
        List<String> sentMessages();
    }

    private static class RecordingMessageRegistry extends MessageRegistry {
        record RenderCall(String key, Map<String, String> placeholders, String message) {}

        private final List<RenderCall> renderedCalls = new ArrayList<>();

        RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            Component component = super.renderWithPrefix(snapshot, key, placeholders);
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(),
                    ColorParser.serialize(component)));
            return component;
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Map.of());
        }

        List<RenderCall> renderedCalls() {
            return List.copyOf(renderedCalls);
        }

        List<String> renderedMessages() {
            return renderedCalls.stream().map(RenderCall::message).toList();
        }

        RenderCall lastCall() {
            return renderedCalls.getLast();
        }

        void clear() {
            renderedCalls.clear();
        }
    }
}
