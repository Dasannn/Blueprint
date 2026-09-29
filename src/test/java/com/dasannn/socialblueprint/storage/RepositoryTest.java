package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
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
    @DisplayName("T-018: AuditRepository persists and retrieves audit trail per SB-058 and SB-064")
    void auditRepositoryOperations() {
        AuditEvent event = new AuditEvent("AdminAlex", "status_reset", "TargetBob", "+10", "0", baseTime);
        AuditEvent saved = auditRepo.save(event);

        assertThat(saved.id()).isPositive();
        assertThat(saved.actor()).isEqualTo("AdminAlex");

        List<AuditEvent> targetAudits = auditRepo.findByTarget("TargetBob");
        assertThat(targetAudits).hasSize(1);
        assertThat(targetAudits.get(0).operation()).isEqualTo("status_reset");
        assertThat(targetAudits.get(0).before()).isEqualTo("+10");
        assertThat(targetAudits.get(0).after()).isEqualTo("0");
    }
}
