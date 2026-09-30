package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.KillPenaltyConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.ConfidenceCalculator;
import com.dasannn.socialblueprint.domain.ConfidenceConfig;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisCalculator;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.DuelRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class KillPenaltyTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private StatusCache statusCache;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private AuditRepository auditRepo;
    private DuelRepository duelRepo;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private DuelService duelService;
    private DuelCombatListener listener;

    private final Map<UUID, Player> mockPlayers = new HashMap<>();
    private final Map<String, PlayerLookup.KnownPlayer> knownPlayers = new HashMap<>();
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

        Logger logger = Logger.getLogger("KillPenaltyTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        auditRepo = new AuditRepository(storage);
        duelRepo = new DuelRepository(storage);

        PlayerLookup lookup = nameOrUuid -> Optional.ofNullable(knownPlayers.get(nameOrUuid.toLowerCase()));

        duelService = new DuelService(
                duelRepo,
                auditRepo,
                psychosisRepo,
                configManager,
                messageRegistry,
                lookup,
                Runnable::run,
                (d, r) -> () -> {},
                (p, c) -> {},
                c -> {}
        );

        listener = new DuelCombatListener(duelService, psychosisRepo, configManager, reputationRepo);
    }

    @AfterEach
    void tearDown() {
        if (duelService != null) {
            duelService.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private Player createMockPlayer(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        PlayerId pid = PlayerId.of(uuid);

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("getKiller".equals(mName)) return null;
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );

        mockPlayers.put(uuid, player);
        knownPlayers.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(pid, name, true));
        knownPlayers.put(uuid.toString(), new PlayerLookup.KnownPlayer(pid, name, true));
        return player;
    }

    @Test
    @DisplayName("T-130: An open-world kill writes a SYSTEM_KILL reputation event with actor null and delta from YAML")
    void t130_openKillWritesSystemKillEvent() {
        Player killer = createMockPlayer("Attacker");
        Player victim = createMockPlayer("Victim");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        listener.handleDeath(victimId, killerId, baseTime, snapshot).join();

        // Check reputation event written for killer
        List<ReputationEvent> events = reputationRepo.findByTarget(killerId);
        assertThat(events).hasSize(1);
        ReputationEvent event = events.getFirst();
        assertThat(event.actor()).isNull();
        assertThat(event.target()).isEqualTo(killerId);
        assertThat(event.kind()).isEqualTo(HonorKind.SYSTEM_KILL);
        assertThat(event.delta()).isEqualTo(-1);
        assertThat(event.cost()).isEqualTo(0.0);
        assertThat(event.reason()).isEqualTo("kill-penalty.reason");
        assertThat(event.createdAt()).isEqualTo(baseTime);

        // Victim receives no reputation event
        assertThat(reputationRepo.findByTarget(victimId)).isEmpty();

        // Psychosis event is also written
        List<PsychosisEvent> kills = psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10));
        assertThat(kills).hasSize(1);
        assertThat(kills.getFirst().context()).isEqualTo(CombatContext.OPEN);
    }

    @Test
    @DisplayName("T-131: SYSTEM_KILL does not raise Confidence and metrics remain separate")
    void t131_metricsSeparation() {
        PlayerId killerId = PlayerId.of(UUID.randomUUID());
        PlayerId victimId = PlayerId.of(UUID.randomUUID());

        // Create confidence calculator and psychosis calculator
        ConfidenceCalculator confidenceCalculator = new ConfidenceCalculator(
                new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30))
        );
        PsychosisCalculator psychosisCalculator = new PsychosisCalculator(
                configManager.snapshot().config().psychosis().toDomain()
        );

        // 1. Initial state: no events
        assertThat(confidenceCalculator.calculate(List.of(), baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(confidenceCalculator.calculateScore(List.of(), baseTime)).isEqualTo(0.0);
        assertThat(psychosisCalculator.calculate(killerId, List.of(), baseTime)).isEqualTo(PsychosisLevel.LOW);

        // 2. Add 5 SYSTEM_KILL events
        List<ReputationEvent> repEvents = List.of(
                new ReputationEvent(0L, null, killerId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime.minusSeconds(40)),
                new ReputationEvent(0L, null, killerId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime.minusSeconds(30)),
                new ReputationEvent(0L, null, killerId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime.minusSeconds(20)),
                new ReputationEvent(0L, null, killerId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime.minusSeconds(10)),
                new ReputationEvent(0L, null, killerId, -1, HonorKind.SYSTEM_KILL, 0.0, "kill-penalty.reason", baseTime)
        );

        // Assert Confidence is strictly 0.0 / UNKNOWN (SYSTEM is not a distinct rater under SB-003)
        assertThat(confidenceCalculator.countDistinctActors(repEvents)).isZero();
        assertThat(confidenceCalculator.calculateScore(repEvents, baseTime)).isEqualTo(0.0);
        assertThat(confidenceCalculator.calculate(repEvents, baseTime)).isEqualTo(ConfidenceLevel.UNKNOWN);

        // Assert status penalty does NOT change Psychosis
        // Status drops to -5
        Status statusWithPenalties = Status.fromEvents(repEvents);
        assertThat(statusWithPenalties.value()).isEqualTo(-5);
        // But psychosis derived from 0 open kills is still LOW
        assertThat(psychosisCalculator.calculate(killerId, List.of(), baseTime)).isEqualTo(PsychosisLevel.LOW);

        // Assert Psychosis does NOT derive from or affect status score
        // High open kills (e.g. 10 kills) results in EXTREME Psychosis
        List<PsychosisEvent> kills = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            kills.add(new PsychosisEvent(killerId, victimId, CombatContext.OPEN, baseTime));
        }
        PsychosisLevel highPsychosis = psychosisCalculator.calculate(killerId, kills, baseTime);
        assertThat(highPsychosis).isEqualTo(PsychosisLevel.EXTREME);
        // Status remains exactly -5 (unchanged by Psychosis)
        assertThat(statusWithPenalties.value()).isEqualTo(-5);
    }

    @Test
    @DisplayName("T-132: Pair cooldown guards: one penalty per killer-victim pair inside pair-cooldown window")
    void t132_pairCooldownGuards() {
        Player killer = createMockPlayer("Hunter");
        Player victim = createMockPlayer("Prey");
        Player otherVictim = createMockPlayer("Prey2");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());
        PlayerId otherVictimId = PlayerId.of(otherVictim.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();

        // Kill 1 at t=0: first kill between (killer, victim) -> penalty applied
        listener.handleDeath(victimId, killerId, baseTime, snapshot).join();
        assertThat(reputationRepo.findByTarget(killerId)).hasSize(1);
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-1);

        // Kill 2 at t + 10m: within 30m pair cooldown -> no penalty, but Psychosis rises
        Instant tPlus10m = baseTime.plus(Duration.ofMinutes(10));
        listener.handleDeath(victimId, killerId, tPlus10m, snapshot).join();
        assertThat(reputationRepo.findByTarget(killerId)).hasSize(1); // Still only 1 reputation event
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-1); // Status untouched
        // But 2 open kills exist in Psychosis repository
        assertThat(psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10))).hasSize(2);

        // Kill 3 at t + 15m against DIFFERENT victim (otherVictim) -> different pair, penalty applied!
        Instant tPlus15m = baseTime.plus(Duration.ofMinutes(15));
        listener.handleDeath(otherVictimId, killerId, tPlus15m, snapshot).join();
        assertThat(reputationRepo.findByTarget(killerId)).hasSize(2); // Second reputation event
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-2);

        // Kill 4 at t + 45m against original victim: >30m after last kill between this pair -> penalty applied!
        Instant tPlus45m = baseTime.plus(Duration.ofMinutes(45));
        listener.handleDeath(victimId, killerId, tPlus45m, snapshot).join();
        assertThat(reputationRepo.findByTarget(killerId)).hasSize(3); // Third reputation event
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-3);
    }

    @Test
    @DisplayName("T-132: max-loss caps how much status one killer can lose inside cap-window while Psychosis still rises")
    void t132_maxLossCapGuards() {
        Player killer = createMockPlayer("SerialKiller");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());

        // Configure max-loss = 3 with default delta = -1
        KillPenaltyConfigSection customPenalty = new KillPenaltyConfigSection(
                -1,
                Duration.ofMinutes(1), // 1 min pair cooldown for easy testing
                Duration.ofDays(7),
                3, // Cap at 3 loss
                Collections.emptySet()
        );
        RuntimeSnapshot customSnapshot = new RuntimeSnapshot(
                configManager.snapshot().config().withKillPenalty(customPenalty),
                configManager.snapshot().messages()
        );

        // Kill 3 distinct victims at 5 minute intervals -> status drops to -3 (reaching maxLoss 3)
        for (int i = 0; i < 3; i++) {
            Player victim = createMockPlayer("Victim_" + i);
            PlayerId victimId = PlayerId.of(victim.getUniqueId());
            Instant killTime = baseTime.plus(Duration.ofMinutes(i * 5));
            listener.handleDeath(victimId, killerId, null, killTime, customSnapshot).join();
        }

        assertThat(reputationRepo.findByTarget(killerId)).hasSize(3);
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-3);
        assertThat(psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10))).hasSize(3);

        // 4th kill with a new victim past the cap (loss = 3 == maxLoss 3):
        Player victim4 = createMockPlayer("Victim_4");
        PlayerId victim4Id = PlayerId.of(victim4.getUniqueId());
        Instant killTime4 = baseTime.plus(Duration.ofMinutes(20));
        listener.handleDeath(victim4Id, killerId, null, killTime4, customSnapshot).join();

        // Reputation event was NOT written, status remains capped at -3
        assertThat(reputationRepo.findByTarget(killerId)).hasSize(3);
        assertThat(reputationRepo.getStatus(killerId).value()).isEqualTo(-3);

        // But Psychosis still rises (4 kills recorded in psychosis_event)
        assertThat(psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10))).hasSize(4);
    }

    @Test
    @DisplayName("T-133: No penalty inside a duel")
    void t133_noPenaltyInsideDuel() {
        Player killer = createMockPlayer("DuelistA");
        Player victim = createMockPlayer("DuelistB");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(killerId, Map.of("s1", Set.of(killerId), "s2", Set.of(victimId)), snapshot);
        duelService.accept(victimId, null, snapshot);
        assertThat(duelService.areInSameActiveDuel(killerId, victimId)).isTrue();

        listener.handleDeath(victimId, killerId, baseTime, snapshot).join();

        // No reputation event written
        assertThat(reputationRepo.findByTarget(killerId)).isEmpty();
        assertThat(reputationRepo.findByTarget(victimId)).isEmpty();

        // Duel event written, not open kill
        assertThat(psychosisRepo.countOpenKillsSince(killerId, baseTime.minusSeconds(10))).isZero();
    }

    @Test
    @DisplayName("T-133: No penalty when killer cannot be identified (null killer or suicide)")
    void t133_noPenaltyWhenKillerUnidentifiedOrSelf() {
        Player victim = createMockPlayer("FallVictim");
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();

        // Case A: Null killer (environmental death)
        listener.handleDeath(victimId, null, baseTime, snapshot).join();
        assertThat(reputationRepo.findByTarget(victimId)).isEmpty();

        // Case B: Suicide (killer equals victim)
        listener.handleDeath(victimId, victimId, baseTime.plusSeconds(5), snapshot).join();
        assertThat(reputationRepo.findByTarget(victimId)).isEmpty();
    }

    @Test
    @DisplayName("T-133: No penalty in an exempt world")
    void t133_noPenaltyInExemptWorld() {
        Player killer = createMockPlayer("PvPer");
        Player victim = createMockPlayer("PvPTarget");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        // Configure "pvp_arena" as exempt world
        KillPenaltyConfigSection exemptPenalty = new KillPenaltyConfigSection(
                -1,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                Set.of("pvp_arena")
        );
        RuntimeSnapshot snapshot = new RuntimeSnapshot(
                configManager.snapshot().config().withKillPenalty(exemptPenalty),
                configManager.snapshot().messages()
        );

        listener.handleDeath(victimId, killerId, "pvp_arena", baseTime, snapshot).join();

        // No reputation event written for exempt world
        assertThat(reputationRepo.findByTarget(killerId)).isEmpty();

        // Open kill still raises Psychosis
        assertThat(psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10))).hasSize(1);
    }

    @Test
    @DisplayName("T-133: No penalty at all when delta is 0 (feature disabled)")
    void t133_noPenaltyWhenDeltaIsZero() {
        Player killer = createMockPlayer("PeacefulKiller");
        Player victim = createMockPlayer("Casualty");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        // delta = 0 disables the feature entirely
        KillPenaltyConfigSection disabledPenalty = new KillPenaltyConfigSection(
                0,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                Collections.emptySet()
        );
        assertThat(disabledPenalty.isEnabled()).isFalse();

        RuntimeSnapshot snapshot = new RuntimeSnapshot(
                configManager.snapshot().config().withKillPenalty(disabledPenalty),
                configManager.snapshot().messages()
        );

        listener.handleDeath(victimId, killerId, null, baseTime, snapshot).join();

        // No reputation event written
        assertThat(reputationRepo.findByTarget(killerId)).isEmpty();

        // Psychosis still rises
        assertThat(psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(10))).hasSize(1);
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
