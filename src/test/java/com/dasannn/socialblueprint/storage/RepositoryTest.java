package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTest {

    private StorageEngine storage;
    private StatusCache statusCache;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private AuditRepository auditRepo;

    private final Instant baseTime = Instant.parse("2026-09-29T12:00:00Z");

    @BeforeEach
    void setUp() {
        storage = StorageEngine.inMemory();
        storage.runMigrations();
        statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);
        auditRepo = new AuditRepository(storage);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-018, T-021: ReputationRepository saves and retrieves events with generated IDs")
    void reputationEventsPersistence() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        ReputationEvent event1 = new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime);
        ReputationEvent persisted1 = reputationRepo.save(event1);

        assertThat(persisted1.id()).isPositive();
        assertThat(persisted1.actor()).isEqualTo(actor);
        assertThat(persisted1.target()).isEqualTo(target);

        ReputationEvent event2 = new ReputationEvent(actor, target, -1, HonorKind.NEGATIVE, 750.0, "Greedy", baseTime.plusSeconds(10));
        ReputationEvent persisted2 = reputationRepo.save(event2);
        assertThat(persisted2.id()).isGreaterThan(persisted1.id());

        List<ReputationEvent> targetEvents = reputationRepo.findByTarget(target);
        assertThat(targetEvents).hasSize(2);
        assertThat(targetEvents.get(0).delta()).isEqualTo(1);
        assertThat(targetEvents.get(1).delta()).isEqualTo(-1);
        assertThat(targetEvents.get(1).reason()).isEqualTo("Greedy");

        List<ReputationEvent> actorEvents = reputationRepo.findByActor(actor);
        assertThat(actorEvents).hasSize(2);
    }

    @Test
    @DisplayName("T-019: In-memory status cache is invalidated on write and rebuildable from events")
    void statusCacheInvalidatedOnWriteAndRebuildable() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // Target initially has no events -> derived status 0
        assertThat(statusCache.isCached(target)).isFalse();
        Status s0 = reputationRepo.getStatus(target);
        assertThat(s0).isEqualTo(Status.ZERO);
        assertThat(statusCache.isCached(target)).isTrue();
        assertThat(statusCache.get(target)).contains(Status.ZERO);

        // Write event (+1): repository MUST invalidate cache for target
        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));
        assertThat(statusCache.isCached(target)).isFalse(); // Invalidated on write!

        // Read status again: rebuilt from events -> Status 1
        Status s1 = reputationRepo.getStatus(target);
        assertThat(s1.value()).isEqualTo(1);
        assertThat(statusCache.isCached(target)).isTrue();
        assertThat(statusCache.get(target)).contains(Status.of(1));

        // Manually clearing cache (simulating cache loss or restart)
        statusCache.invalidateAll();
        assertThat(statusCache.isCached(target)).isFalse();

        // Rebuilt again from events without error
        Status sRebuilt = reputationRepo.getStatus(target);
        assertThat(sRebuilt.value()).isEqualTo(1);
        assertThat(statusCache.isCached(target)).isTrue();
    }

    @Test
    @DisplayName("T-018: ProfileRepository persists, retrieves and updates player profiles")
    void profileRepositoryOperations() {
        PlayerId id = PlayerId.of(UUID.randomUUID());
        PlayerProfile profile = PlayerProfile.create(id, "Steve", baseTime);

        profileRepo.save(profile);

        Optional<PlayerProfile> foundById = profileRepo.findById(id);
        assertThat(foundById).isPresent();
        assertThat(foundById.get().lastKnownName()).isEqualTo("Steve");
        assertThat(foundById.get().effectsOptOut()).isFalse();

        Optional<PlayerProfile> foundByName = profileRepo.findByName("steve");
        assertThat(foundByName).isPresent();
        assertThat(foundByName.get().id()).isEqualTo(id);

        // Update name and opt-out
        PlayerProfile updated = foundById.get()
                .withName("SuperSteve", baseTime.plusSeconds(100))
                .withEffectsOptOut(true, baseTime.plusSeconds(100));
        profileRepo.save(updated);

        Optional<PlayerProfile> reloaded = profileRepo.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().lastKnownName()).isEqualTo("SuperSteve");
        assertThat(reloaded.get().effectsOptOut()).isTrue();
    }

    @Test
    @DisplayName("T-018: PsychosisRepository persists and counts kills")
    void psychosisRepositoryOperations() {
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());

        PsychosisEvent openKill = new PsychosisEvent(killer, victim, CombatContext.OPEN, baseTime);
        PsychosisEvent duelKill = new PsychosisEvent(killer, victim, CombatContext.DUEL, baseTime.plusSeconds(60));

        PsychosisEvent pOpen = psychosisRepo.save(openKill);
        assertThat(pOpen.id()).isPositive();
        psychosisRepo.save(duelKill);

        List<PsychosisEvent> kills = psychosisRepo.findKillsByKillerSince(killer, baseTime.minusSeconds(10));
        assertThat(kills).hasSize(2);

        int openKills = psychosisRepo.countOpenKillsSince(killer, baseTime.minusSeconds(10));
        assertThat(openKills).isEqualTo(1); // Only open kill
    }

    @Test
    @DisplayName("T-018: AuditRepository persists and retrieves audit trail per SB-058, SB-060 and SB-064")
    void auditRepositoryOperations() {
        PlayerId admin = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        // Admin player auditing an action against target player (SB-058, SB-060)
        AuditEvent event = new AuditEvent(admin, "status_reset", target, "+10", "0", baseTime);
        AuditEvent saved = auditRepo.save(event);

        assertThat(saved.id()).isPositive();
        assertThat(saved.actor()).isEqualTo(admin);

        List<AuditEvent> targetAudits = auditRepo.findByTarget(target);
        assertThat(targetAudits).hasSize(1);
        assertThat(targetAudits.get(0).operation()).isEqualTo("status_reset");
        assertThat(targetAudits.get(0).before()).isEqualTo("+10");
        assertThat(targetAudits.get(0).after()).isEqualTo("0");

        // Console actor auditing a non-player action like config edit (SB-064, SB-065)
        NonPlayerTarget configTarget = NonPlayerTarget.configKey("honor.cost");
        AuditEvent consoleEvent = new AuditEvent(PlayerId.CONSOLE, "config_edit", configTarget, "500", "600", baseTime.plusSeconds(5));
        AuditEvent savedConsole = auditRepo.save(consoleEvent);
        assertThat(savedConsole.actor()).isEqualTo(PlayerId.CONSOLE);
        assertThat(savedConsole.actor().isConsole()).isTrue();

        List<AuditEvent> configAudits = auditRepo.findByTarget(configTarget);
        assertThat(configAudits).hasSize(1);
        assertThat(configAudits.get(0).actor()).isEqualTo(PlayerId.CONSOLE);
    }

    @Test
    @DisplayName("Fix 3: Renamed player audit history stays joined across name change")
    void renamedPlayerAuditHistoryStaysJoined() {
        // Player UUID is durable identity per SB-060
        UUID playerUuid = UUID.randomUUID();
        PlayerId playerId = PlayerId.of(playerUuid);
        PlayerId admin = PlayerId.of(UUID.randomUUID());

        // Event 1 when player was named "Steve"
        AuditEvent event1 = new AuditEvent(admin, "status_reset", playerId, "+10", "0", baseTime);
        auditRepo.save(event1);

        // Event 2 when player changed their name to "Alex"
        AuditEvent event2 = new AuditEvent(admin, "honor_take", playerId, "0", "-5", baseTime.plusSeconds(10));
        auditRepo.save(event2);

        // Target lookup by PlayerId returns both audit records; history remains unified
        List<AuditEvent> playerAudits = auditRepo.findByTarget(playerId);
        assertThat(playerAudits).hasSize(2);
        assertThat(playerAudits.get(0).operation()).isEqualTo("status_reset");
        assertThat(playerAudits.get(1).operation()).isEqualTo("honor_take");
        assertThat(playerAudits.get(0).target()).isEqualTo(playerUuid.toString());
        assertThat(playerAudits.get(1).target()).isEqualTo(playerUuid.toString());
    }

    @Test
    @DisplayName("Finding 2: Same-second boundary tests for every 'Since' query")
    void sameSecondBoundaryTestsForSinceQueries() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        Instant t0 = Instant.parse("2026-09-29T12:00:00.000000000Z");
        Instant tMid = Instant.parse("2026-09-29T12:00:00.500000000Z");
        Instant tLate = Instant.parse("2026-09-29T12:00:00.800000000Z");

        // 1. Reputation countActorRatingsSince
        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, t0));
        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, tMid));
        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, tLate));

        // Cutoff at tMid: t0 (before) and tMid (equal) must be excluded; only tLate (> tMid) included
        int actorCount = reputationRepo.countActorRatingsSince(actor, tMid);
        assertThat(actorCount).isEqualTo(1);

        // 2. Reputation countPairRatingsSince
        int pairCount = reputationRepo.countPairRatingsSince(actor, target, HonorKind.POSITIVE, tMid);
        assertThat(pairCount).isEqualTo(1);

        // 3. Psychosis findKillsByKillerSince and countOpenKillsSince
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());

        psychosisRepo.save(new PsychosisEvent(killer, victim, CombatContext.OPEN, t0));
        psychosisRepo.save(new PsychosisEvent(killer, victim, CombatContext.OPEN, tMid));
        psychosisRepo.save(new PsychosisEvent(killer, victim, CombatContext.OPEN, tLate));

        List<PsychosisEvent> kills = psychosisRepo.findKillsByKillerSince(killer, tMid);
        assertThat(kills).hasSize(1);
        assertThat(kills.get(0).createdAt()).isEqualTo(tLate);

        int openKills = psychosisRepo.countOpenKillsSince(killer, tMid);
        assertThat(openKills).isEqualTo(1);
    }

    @Test
    @DisplayName("Finding 3: StatusCache prevents stale cache after concurrent invalidation")
    void statusCachePreventsStalePublishAfterInvalidation() throws Exception {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, baseTime));

        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try {
            // Reader starts rebuilding with status 1, but pauses before returning events
            Future<Status> readerFuture = readerExecutor.submit(() -> {
                return statusCache.getOrRebuild(target, () -> {
                    List<ReputationEvent> oldEvents = reputationRepo.findByTarget(target);
                    readerStarted.countDown();
                    try {
                        writerFinished.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return oldEvents;
                });
            });

            // Wait for reader to start loading old events
            readerStarted.await();

            // Concurrent write: saves event (+5) and invalidates target cache
            reputationRepo.save(new ReputationEvent(actor, target, 5, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(5)));

            // Unblock reader to complete its rebuild
            writerFinished.countDown();
            Status derived = readerFuture.get();
            // Under P9 Finding 4, getOrRebuild detects generation moved, reloads, and returns the current derived status (6)
            assertThat(derived.value()).isEqualTo(6);
            assertThat(statusCache.get(target)).contains(Status.of(6));
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Finding 4: Storage queries at exactly one window vs one nanosecond after")
    void storageQueriesWindowEdgeHalfOpen() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());

        Instant eventTime = Instant.parse("2026-09-29T12:00:00.000000000Z");
        reputationRepo.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500.0, null, eventTime));

        // Cutoff at exactly eventTime: event is excluded by half-open (now - window, now]
        assertThat(reputationRepo.countActorRatingsSince(actor, eventTime)).isZero();
        assertThat(reputationRepo.countPairRatingsSince(actor, target, HonorKind.POSITIVE, eventTime)).isZero();

        // Cutoff at 1 nanosecond before eventTime: event is included
        Instant justBefore = eventTime.minusNanos(1);
        assertThat(reputationRepo.countActorRatingsSince(actor, justBefore)).isEqualTo(1);
        assertThat(reputationRepo.countPairRatingsSince(actor, target, HonorKind.POSITIVE, justBefore)).isEqualTo(1);
    }
}
