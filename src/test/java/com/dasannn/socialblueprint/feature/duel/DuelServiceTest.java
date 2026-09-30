package com.dasannn.socialblueprint.feature.duel;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.duel.ActiveDuelSession;
import com.dasannn.socialblueprint.domain.duel.DisconnectClassification;
import com.dasannn.socialblueprint.domain.duel.DuelChallenge;
import com.dasannn.socialblueprint.domain.duel.DuelRecord;
import com.dasannn.socialblueprint.domain.duel.DuelState;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.DuelRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DuelServiceTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private DuelRepository duelRepository;
    private AuditRepository auditRepository;
    private PsychosisRepository psychosisRepository;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private TestTimerScheduler timerScheduler;

    private final Map<PlayerId, List<Component>> receivedMessages = new HashMap<>();
    private final List<Component> broadcastMessages = new CopyOnWriteArrayList<>();
    private final Map<String, PlayerLookup.KnownPlayer> knownPlayers = new HashMap<>();

    private DuelService duelService;

    static class TestTimerScheduler implements DuelService.TimerScheduler {
        record ScheduledTask(Duration delay, Runnable task, AtomicBoolean cancelled) implements TaskHandle {
            @Override
            public void cancel() {
                cancelled.set(true);
            }
        }
        final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public TaskHandle schedule(Duration delay, Runnable task) {
            ScheduledTask st = new ScheduledTask(delay, task, new AtomicBoolean(false));
            tasks.add(st);
            return st;
        }

        void runPending() {
            for (ScheduledTask st : new ArrayList<>(tasks)) {
                if (!st.cancelled.get()) {
                    st.task.run();
                }
            }
            tasks.clear();
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

        Logger logger = Logger.getLogger("DuelServiceTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        duelRepository = new DuelRepository(storage);
        auditRepository = new AuditRepository(storage);
        psychosisRepository = new PsychosisRepository(storage);
        timerScheduler = new TestTimerScheduler();

        PlayerLookup playerLookup = nameOrUuid -> {
            if (knownPlayers.containsKey(nameOrUuid)) {
                return Optional.of(knownPlayers.get(nameOrUuid));
            }
            return Optional.empty();
        };

        duelService = new DuelService(
                duelRepository,
                auditRepository,
                psychosisRepository,
                configManager,
                messageRegistry,
                playerLookup,
                Runnable::run,
                timerScheduler,
                (pid, comp) -> receivedMessages.computeIfAbsent(pid, k -> new ArrayList<>()).add(comp),
                broadcastMessages::add
        );
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

    private PlayerId registerPlayer(String name) {
        UUID uuid = UUID.randomUUID();
        PlayerId pid = PlayerId.of(uuid);
        PlayerLookup.KnownPlayer kp = new PlayerLookup.KnownPlayer(pid, name, true);
        knownPlayers.put(name.toLowerCase(), kp);
        knownPlayers.put(uuid.toString(), kp);
        return pid;
    }

    @Test
    @DisplayName("T-060 / SB-030: 1v1 challenge, accept, and active duel start")
    void challengeAndAccept1v1StartsDuel() {
        PlayerId p1 = registerPlayer("Alice");
        PlayerId p2 = registerPlayer("Bob");

        RuntimeSnapshot snapshot = configManager.snapshot();

        // Alice challenges Bob
        Map<String, Set<PlayerId>> sides = Map.of(
                "side_1", Set.of(p1),
                "side_2", Set.of(p2)
        );

        DuelService.ChallengeResult cResult = duelService.challenge(p1, sides, snapshot);
        assertThat(cResult).isInstanceOf(DuelService.ChallengeResult.Success.class);
        DuelChallenge challenge = ((DuelService.ChallengeResult.Success) cResult).challenge();

        assertThat(duelService.pendingChallengeCount()).isEqualTo(1);
        assertThat(duelService.activeDuelCount()).isEqualTo(0);
        assertThat(duelService.isInActiveDuel(p1)).isFalse();
        assertThat(duelService.isInActiveDuel(p2)).isFalse();

        // Bob accepts challenge
        DuelService.AcceptResult aResult = duelService.accept(p2, "Alice", snapshot);
        assertThat(aResult).isInstanceOf(DuelService.AcceptResult.DuelStarted.class);
        ActiveDuelSession session = ((DuelService.AcceptResult.DuelStarted) aResult).session();

        assertThat(duelService.pendingChallengeCount()).isEqualTo(0);
        assertThat(duelService.activeDuelCount()).isEqualTo(1);
        assertThat(duelService.isInActiveDuel(p1)).isTrue();
        assertThat(duelService.isInActiveDuel(p2)).isTrue();
        assertThat(duelService.areInSameActiveDuel(p1, p2)).isTrue();

        // Verify active duel was saved in SQLite repository
        Optional<DuelRecord> savedRecord = duelRepository.findById(session.id());
        assertThat(savedRecord).isPresent();
        assertThat(savedRecord.get().state()).isEqualTo(DuelState.ACTIVE);
        assertThat(savedRecord.get().participants()).hasSize(2);
    }

    @Test
    @DisplayName("T-060: Cannot challenge if already in active duel or already challenging")
    void cannotChallengeWhenBusy() {
        PlayerId p1 = registerPlayer("P1");
        PlayerId p2 = registerPlayer("P2");
        PlayerId p3 = registerPlayer("P3");
        RuntimeSnapshot snapshot = configManager.snapshot();

        // P1 challenges P2
        duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);

        // P1 tries to challenge P3 while already having an outgoing challenge
        DuelService.ChallengeResult busyResult = duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p3)), snapshot);
        assertThat(busyResult).isInstanceOf(DuelService.ChallengeResult.AlreadyChallenging.class);

        // P2 accepts -> P1 and P2 enter active duel
        duelService.accept(p2, null, snapshot);

        // P1 tries to challenge P3 while in active duel
        DuelService.ChallengeResult inDuelResult = duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p3)), snapshot);
        assertThat(inDuelResult).isInstanceOf(DuelService.ChallengeResult.AlreadyInDuel.class);

        // P3 tries to challenge P2 (who is in active duel)
        DuelService.ChallengeResult targetInDuelResult = duelService.challenge(p3, Map.of("s1", Set.of(p3), "s2", Set.of(p2)), snapshot);
        assertThat(targetInDuelResult).isInstanceOf(DuelService.ChallengeResult.TargetAlreadyInDuel.class);

        // Cannot duel self
        DuelService.ChallengeResult selfResult = duelService.challenge(p3, Map.of("s1", Set.of(p3), "s2", Set.of(p3)), snapshot);
        assertThat(selfResult).isInstanceOf(DuelService.ChallengeResult.CannotDuelSelf.class);
    }

    @Test
    @DisplayName("DoD 2 / T-060: Unaccepted challenge expires cleanly with NO orphaned rows in SQLite")
    void challengeExpiresCleanlyWithNoOrphanedRows() {
        PlayerId p1 = registerPlayer("Challenger");
        PlayerId p2 = registerPlayer("Opponent");
        RuntimeSnapshot snapshot = configManager.snapshot();

        DuelService.ChallengeResult cResult = duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);
        assertThat(cResult).isInstanceOf(DuelService.ChallengeResult.Success.class);
        String challengeId = ((DuelService.ChallengeResult.Success) cResult).challenge().id();

        assertThat(duelService.pendingChallengeCount()).isEqualTo(1);
        assertThat(timerScheduler.tasks).hasSize(1);

        // Run the scheduled expiry task
        timerScheduler.runPending();

        // Challenge is expired and cleaned up from memory
        assertThat(duelService.pendingChallengeCount()).isEqualTo(0);
        assertThat(duelService.getPendingChallenge(challengeId)).isNull();

        // Assert DoD 2: Leaves NO trace in SQLite - absolutely zero rows written!
        assertThat(duelRepository.findById(challengeId)).isEmpty();
        assertThat(duelRepository.findActiveDuels()).isEmpty();
    }

    @Test
    @DisplayName("T-060: Group duel requires EVERY member consent; deny cancels challenge with zero rows")
    void groupDuelConsentAndDenialLifecycle() {
        PlayerId blue1 = registerPlayer("Blue1");
        PlayerId blue2 = registerPlayer("Blue2");
        PlayerId red1 = registerPlayer("Red1");
        PlayerId red2 = registerPlayer("Red2");

        RuntimeSnapshot snapshot = configManager.snapshot();

        Map<String, Set<PlayerId>> sides = Map.of(
                "blue", Set.of(blue1, blue2),
                "red", Set.of(red1, red2)
        );

        // Blue1 (challenger) sends group challenge
        DuelService.ChallengeResult cResult = duelService.challenge(blue1, sides, snapshot);
        assertThat(cResult).isInstanceOf(DuelService.ChallengeResult.Success.class);
        String challengeId = ((DuelService.ChallengeResult.Success) cResult).challenge().id();

        // Red1 accepts -> consent recorded (2/4), duel does not start
        DuelService.AcceptResult a1 = duelService.accept(red1, null, snapshot);
        assertThat(a1).isInstanceOf(DuelService.AcceptResult.ConsentRecorded.class);
        DuelService.AcceptResult.ConsentRecorded cr1 = (DuelService.AcceptResult.ConsentRecorded) a1;
        assertThat(cr1.acceptedCount()).isEqualTo(2);
        assertThat(cr1.totalCount()).isEqualTo(4);
        assertThat(duelService.activeDuelCount()).isEqualTo(0);

        // Blue2 accepts -> consent recorded (3/4), duel still does not start
        DuelService.AcceptResult a2 = duelService.accept(blue2, null, snapshot);
        assertThat(a2).isInstanceOf(DuelService.AcceptResult.ConsentRecorded.class);
        DuelService.AcceptResult.ConsentRecorded cr2 = (DuelService.AcceptResult.ConsentRecorded) a2;
        assertThat(cr2.acceptedCount()).isEqualTo(3);
        assertThat(cr2.totalCount()).isEqualTo(4);
        assertThat(duelService.activeDuelCount()).isEqualTo(0);

        // Red2 denies challenge -> entire challenge is cancelled
        DuelService.DenyResult dResult = duelService.deny(red2, null, snapshot);
        assertThat(dResult).isInstanceOf(DuelService.DenyResult.Denied.class);

        assertThat(duelService.pendingChallengeCount()).isEqualTo(0);
        assertThat(duelService.activeDuelCount()).isEqualTo(0);

        // Zero rows written in SQLite
        assertThat(duelRepository.findById(challengeId)).isEmpty();
    }

    @Test
    @DisplayName("T-060: Leave allows challenger to cancel pending challenge or participant to forfeit active duel")
    void leaveLifecycle() {
        PlayerId p1 = registerPlayer("P1");
        PlayerId p2 = registerPlayer("P2");
        RuntimeSnapshot snapshot = configManager.snapshot();

        // 1. Leave while having outgoing challenge cancels it
        duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);
        DuelService.LeaveResult leaveChallenge = duelService.leave(p1, snapshot);
        assertThat(leaveChallenge).isInstanceOf(DuelService.LeaveResult.ChallengeCancelled.class);
        assertThat(duelService.pendingChallengeCount()).isEqualTo(0);

        // 2. Leave while in active duel forfeits duel
        duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);
        duelService.accept(p2, null, snapshot);
        assertThat(duelService.activeDuelCount()).isEqualTo(1);

        DuelService.LeaveResult leaveActive = duelService.leave(p2, snapshot);
        assertThat(leaveActive).isInstanceOf(DuelService.LeaveResult.LeftDuel.class);
        DuelService.LeaveResult.LeftDuel ld = (DuelService.LeaveResult.LeftDuel) leaveActive;
        assertThat(ld.duelEnded()).isTrue();
        assertThat(ld.winners()).containsExactly(p1);

        assertThat(duelService.activeDuelCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("DoD 3 / T-063: Normal disconnect (no combat damage) uses configurable grace period and permits reconnect")
    void normalDisconnectReconnectGracePeriod() {
        PlayerId p1 = registerPlayer("P1");
        PlayerId p2 = registerPlayer("P2");
        RuntimeSnapshot snapshot = configManager.snapshot();

        duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);
        duelService.accept(p2, null, snapshot);

        // P2 quits without taking combat damage -> classified as NORMAL_DISCONNECT
        Instant quitTime = Instant.now();
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(p2, quitTime, snapshot);
        assertThat(disc).contains(DisconnectClassification.NORMAL_DISCONNECT);

        // Duel is STILL ACTIVE during grace period
        assertThat(duelService.isInActiveDuel(p1)).isTrue();
        assertThat(duelService.isInActiveDuel(p2)).isTrue();
        ActiveDuelSession session = duelService.getActiveDuel(p2);
        assertThat(session.isDisconnected(p2)).isTrue();

        // P2 reconnects within grace period
        duelService.handlePlayerJoin(p2, snapshot);

        // Reconnected! Disconnected flag is cleared and duel continues
        assertThat(session.isDisconnected(p2)).isFalse();
        assertThat(duelService.activeDuelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DoD 3 / T-063: Normal disconnect grace period timeout eliminates player and concludes duel")
    void normalDisconnectTimeoutConcludesDuel() {
        PlayerId p1 = registerPlayer("P1");
        PlayerId p2 = registerPlayer("P2");
        RuntimeSnapshot snapshot = configManager.snapshot();

        duelService.challenge(p1, Map.of("s1", Set.of(p1), "s2", Set.of(p2)), snapshot);
        duelService.accept(p2, null, snapshot);

        // P2 quits without combat damage
        duelService.handlePlayerQuit(p2, Instant.now(), snapshot);
        assertThat(timerScheduler.tasks).isNotEmpty();

        // Grace period timer expires
        timerScheduler.runPending();

        // Duel concluded due to timeout
        assertThat(duelService.activeDuelCount()).isEqualTo(0);
        assertThat(duelService.isInActiveDuel(p1)).isFalse();
        assertThat(duelService.isInActiveDuel(p2)).isFalse();
    }

    @Test
    @DisplayName("DoD 3 / T-063: Combat log disconnect immediately forfeits, logs audit event, and broadcasts social consequence")
    void combatLogDisconnectImmediateForfeitAndAudit() {
        PlayerId victim = registerPlayer("Coward");
        PlayerId attacker = registerPlayer("Attacker");
        RuntimeSnapshot snapshot = configManager.snapshot();

        duelService.challenge(attacker, Map.of("s1", Set.of(attacker), "s2", Set.of(victim)), snapshot);
        duelService.accept(victim, null, snapshot);

        ActiveDuelSession session = duelService.getActiveDuel(victim);
        String duelId = session.id();

        // Attacker deals combat damage to victim
        Instant damageTime = Instant.now();
        duelService.recordCombatDamage(victim, attacker, damageTime);

        // Victim quits 2 seconds later (well within 10s default combatLogWindow)
        Instant quitTime = damageTime.plusSeconds(2);
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(victim, quitTime, snapshot);

        // Assert classification is COMBAT_LOG
        assertThat(disc).contains(DisconnectClassification.COMBAT_LOG);

        // Assert immediate elimination/forfeiture
        assertThat(duelService.activeDuelCount()).isEqualTo(0);
        assertThat(duelService.isInActiveDuel(victim)).isFalse();

        // Assert audit trail was recorded per T-063 and ARCHITECTURE.md §4
        List<AuditEvent> audits = auditRepository.findByTarget(victim);
        assertThat(audits).hasSize(1);
        AuditEvent audit = audits.getFirst();
        assertThat(audit.actor()).isEqualTo(victim);
        assertThat(audit.operation()).isEqualTo("DUEL_COMBAT_LOG");
        assertThat(audit.before()).isEqualTo("ACTIVE");
        assertThat(audit.after()).isEqualTo("FORFEIT_COMBAT_LOG");

        // Assert social consequence: broadcast was triggered exposing cowardice
        assertThat(broadcastMessages).isNotEmpty();

        // Assert duel record in SQLite was updated to ENDED
        Optional<DuelRecord> duelRecord = duelRepository.findById(duelId);
        assertThat(duelRecord).isPresent();
        assertThat(duelRecord.get().state()).isEqualTo(DuelState.ENDED);
    }

    @Test
    @DisplayName("T-063: Combat damage older than combat-log-window is classified as NORMAL_DISCONNECT")
    void damageOlderThanWindowIsNormalDisconnect() {
        PlayerId victim = registerPlayer("P1");
        PlayerId attacker = registerPlayer("P2");
        RuntimeSnapshot snapshot = configManager.snapshot();

        duelService.challenge(attacker, Map.of("s1", Set.of(attacker), "s2", Set.of(victim)), snapshot);
        duelService.accept(victim, null, snapshot);

        // Damage at t0
        Instant damageTime = Instant.now().minusSeconds(20);
        duelService.recordCombatDamage(victim, attacker, damageTime);

        // Victim quits 20 seconds later (default window is 10s, so window has passed)
        Instant quitTime = Instant.now();
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(victim, quitTime, snapshot);

        // Classified as NORMAL_DISCONNECT, NOT combat log
        assertThat(disc).contains(DisconnectClassification.NORMAL_DISCONNECT);
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
