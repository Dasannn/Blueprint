package com.dasannn.socialblueprint.feature.legacy;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.command.StatusCommandExecutor;
import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
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

        LegacyPlayerResolver testResolver = nameOrUuid -> {
            lastResolutionThread.set(Thread.currentThread());
            if (knownResolutions.containsKey(nameOrUuid.toLowerCase())) {
                return Optional.of(PlayerId.of(knownResolutions.get(nameOrUuid.toLowerCase())));
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
                testResolver,
                tempDir,
                Runnable::run,
                () -> consoleSender,
                logger
        );

        PlayerLookup testLookup = nameOrUuid -> {
            if (knownResolutions.containsKey(nameOrUuid.toLowerCase())) {
                UUID uuid = knownResolutions.get(nameOrUuid.toLowerCase());
                return Optional.of(new PlayerLookup.KnownPlayer(PlayerId.of(uuid), nameOrUuid, true));
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
    @DisplayName("T-090: Reads old config with name and UUID rows, writes exactly one ReputationEvent per player")
    void readOldConfig_writesExactReputationEventPerPlayer() throws Exception {
        UUID uuidSteve = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID uuidAlex = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID uuidDirect = UUID.fromString("33333333-3333-3333-3333-333333333333");

        knownResolutions.put("steve", uuidSteve);

        // Old config content matching baseline playerstatus format
        String oldYaml = """
            playerList:
              Steve:
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

        // 1. Verify Steve: resolved by name off-thread to uuidSteve
        List<ReputationEvent> steveEvents = reputationRepo.findByTarget(PlayerId.of(uuidSteve));
        assertThat(steveEvents).hasSize(1);
        ReputationEvent steveEvt = steveEvents.getFirst();
        assertThat(steveEvt.actor()).isNull();
        assertThat(steveEvt.kind()).isEqualTo(HonorKind.LEGACY_IMPORT);
        assertThat(steveEvt.delta()).isEqualTo(25);
        assertThat(steveEvt.cost()).isEqualTo(0.0);
        assertThat(steveEvt.reason()).isEqualTo("commands.admin.import.reason");

        // Profile should also be recorded
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

        // Verify thread of name resolution was off-thread (on the storage executor thread)
        assertThat(lastResolutionThread.get()).isNotNull();
        assertThat(lastResolutionThread.get().getName()).contains("socialblueprint-db");
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

        legacyImportService.importLegacyAsync(admin, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Alice is imported
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlice))).hasSize(1);

        // GhostPlayer resolved to no UUID, so nothing can have been written against
        // one: Alice's single event is the only thing the import produced. Asserted
        // through the public repository rather than raw SQL, because reaching past
        // StorageEngine would cross the executor boundary the repositories exist to
        // hold.
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidAlice)))
                .singleElement()
                .satisfies(event -> assertThat(event.delta()).isNotEqualTo(50));

        // Reports skipped to sender and console
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-unresolved", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders()).containsExactlyInAnyOrderEntriesOf(Map.of("player", "GhostPlayer"));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "2", "imported", "1", "skipped", "1"));
        assertThat(senderMessages).hasSize(3);
        assertThat(consoleSentMessages).hasSize(2);
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

        ConfidenceCalculator calc = new ConfidenceCalculator(new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30)));

        assertThat(calc.countDistinctActors(List.of(legacyEvent))).isZero();
        assertThat(calc.calculateScore(List.of(legacyEvent), baseTime)).isEqualTo(0.0);
        assertThat(calc.calculate(List.of(legacyEvent), baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);

        // Even with multiple legacy imports from past migrations
        ReputationEvent legacyEvent2 = new ReputationEvent(
                0L,
                null,
                target,
                -20,
                HonorKind.LEGACY_IMPORT,
                0.0,
                "commands.admin.import.reason",
                baseTime.minus(Duration.ofDays(10))
        );
        assertThat(calc.countDistinctActors(List.of(legacyEvent, legacyEvent2))).isZero();
        assertThat(calc.calculateScore(List.of(legacyEvent, legacyEvent2), baseTime)).isEqualTo(0.0);
        assertThat(calc.calculate(List.of(legacyEvent, legacyEvent2), baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);

        // When a real player rates them, only the real player contributes to Confidence
        PlayerId realActor = PlayerId.of(UUID.randomUUID());
        ReputationEvent realEvent = new ReputationEvent(
                realActor,
                target,
                1,
                HonorKind.POSITIVE,
                500.0,
                null,
                baseTime
        );

        List<ReputationEvent> mixed = List.of(legacyEvent, legacyEvent2, realEvent);
        assertThat(calc.countDistinctActors(mixed)).isEqualTo(1);
        assertThat(calc.calculateScore(mixed, baseTime)).isEqualTo(1.0);
        assertThat(calc.calculate(mixed, baseTime)).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("T-091: Status score reflects legacy import while Confidence remains Unknown")
    void profileViewReflectsLegacyStatusWithUnknownConfidence() {
        UUID uuidCharlie = UUID.fromString("55555555-5555-5555-5555-555555555555");
        PlayerId targetId = PlayerId.of(uuidCharlie);
        knownResolutions.put("charlie", uuidCharlie);

        reputationRepo.save(new ReputationEvent(
                0L,
                null,
                targetId,
                45,
                HonorKind.LEGACY_IMPORT,
                0.0,
                "commands.admin.import.reason",
                baseTime
        ));
        profileRepo.save(PlayerProfile.create(targetId, "Charlie", baseTime));

        PlayerSocialView view = profileService.resolvePlayerAsync("Charlie", configManager.snapshot()).join().orElseThrow();
        assertThat(view.status()).isEqualTo(45);
        assertThat(view.confidence()).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(view.contributors()).isZero();
    }

    @Test
    @DisplayName("T-092: Import is strictly idempotent; second run does not double status")
    void importIsIdempotent_secondRunDoesNotDoubleStatus() throws Exception {
        UUID uuidDave = UUID.fromString("66666666-6666-6666-6666-666666666666");
        knownResolutions.put("dave", uuidDave);

        String yaml = """
            playerList:
              Dave:
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
        assertThat(senderMessages1).hasSize(2);

        Status statusAfterFirst = reputationRepo.getStatus(PlayerId.of(uuidDave));
        assertThat(statusAfterFirst.value()).isEqualTo(35);
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidDave))).hasSize(1);

        // Run 2: Idempotent rerun
        List<String> senderMessages2 = new ArrayList<>();
        Player admin2 = mockPlayer("AdminUser2", senderMessages2, "socialblueprint.admin");
        consoleSentMessages.clear();
        messageRegistry.clear();

        legacyImportService.importLegacyAsync(admin2, legacyFile.getAbsolutePath(), configManager.snapshot()).join();

        // Second run must report 0 imported, 1 skipped as already imported
        assertThat(messageRegistry.renderedCalls()).extracting(RecordingMessageRegistry.RenderCall::key)
                .containsExactly("commands.admin.import.started", "commands.admin.import.skipped-already-imported", "commands.admin.import.summary");
        assertThat(messageRegistry.renderedCalls().get(1).placeholders()).containsExactlyInAnyOrderEntriesOf(Map.of("player", "Dave"));
        assertThat(messageRegistry.lastCall().placeholders()).containsExactlyInAnyOrderEntriesOf(
                Map.of("read", "1", "imported", "0", "skipped", "1"));
        assertThat(senderMessages2).hasSize(3);
        assertThat(consoleSentMessages).hasSize(2);

        // Status score MUST NOT double (remains 35, not 70)
        Status statusAfterSecond = reputationRepo.getStatus(PlayerId.of(uuidDave));
        assertThat(statusAfterSecond.value()).isEqualTo(35);
        assertThat(reputationRepo.findByTarget(PlayerId.of(uuidDave))).hasSize(1);
    }

    @Test
    @DisplayName("T-092: Import reports started and summary to both sender and console")
    void importReportsToBothSenderAndConsole() throws Exception {
        UUID uuidEve = UUID.fromString("77777777-7777-7777-7777-777777777777");
        knownResolutions.put("eve", uuidEve);

        String yaml = """
            playerList:
              Eve:
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
        assertThat(senderMessages).hasSize(2);

        // Check console messages (dual reporting)
        assertThat(consoleSentMessages).hasSize(1);
    }

    @Test
    @DisplayName("T-092: Writing audit record on legacy import")
    void importWritesAuditRecord() throws Exception {
        UUID uuidFrank = UUID.fromString("88888888-8888-8888-8888-888888888888");
        knownResolutions.put("frank", uuidFrank);

        String yaml = """
            playerList:
              Frank:
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
        knownResolutions.put("grace", uuidGrace);

        String yaml = """
            playerList:
              Grace:
                name: 'Grace'
                reputation: 10
              BadScorePlayer:
                name: 'BadScorePlayer'
                reputation: 'NotANumber'
              ExcessivePlayer:
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

        LegacyPlayerResolver blockingResolver = name -> {
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
                blockingResolver,
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

        var f1 = blockingService.importLegacyAsync(admin1, legacyFile.getAbsolutePath(), configManager.snapshot());
        startedLatch.await();

        var f2 = blockingService.importLegacyAsync(admin2, legacyFile.getAbsolutePath(), configManager.snapshot());
        f2.join();

        assertThat(messageRegistry.lastCall().key()).isEqualTo("commands.admin.import.already-running");
        assertThat(admin2Messages).hasSize(1);

        proceedLatch.countDown();
        f1.join();
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
        record RenderCall(String key, Map<String, String> placeholders) {}

        private final List<RenderCall> renderedCalls = new ArrayList<>();

        RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of()));
            return super.renderWithPrefix(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Map.of());
        }

        List<RenderCall> renderedCalls() {
            return List.copyOf(renderedCalls);
        }

        RenderCall lastCall() {
            return renderedCalls.getLast();
        }

        void clear() {
            renderedCalls.clear();
        }
    }
}
