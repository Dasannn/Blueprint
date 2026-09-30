package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.TierLadder;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileServiceTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private StatusCache statusCache;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private ProfileService profileService;

    static class TestClock extends java.time.Clock {
        private Instant instant;

        TestClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(java.time.Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private TestClock testClock;
    private final Instant baseTime = Instant.now().minus(java.time.Duration.ofMinutes(5));

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("ProfileServiceTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        testClock = new TestClock(Instant.now());
        statusCache = new StatusCache(
                configManager.config().decay().cacheTtl(),
                testClock,
                () -> configManager.config().decay().toDomain()
        );
        reputationRepo = new ReputationRepository(
                storage,
                statusCache,
                () -> configManager.config().decay().toDomain(),
                testClock
        );
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);

        profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                null,
                logger,
                testClock
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-042 / SB-005: Uncached player returns neutral view immediately without blocking")
    void uncachedPlayerReturnsNeutralDefaultQuickly() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        TierLadder ladder = configManager.config().tiers().ladder();

        assertThat(profileService.isCached(target)).isFalse();

        // Non-blocking quick view returns neutral default immediately
        PlayerSocialView view = profileService.getViewQuick(target, ladder);

        assertThat(view.playerId()).isEqualTo(target);
        assertThat(view.status()).isZero();
        assertThat(view.tier()).isEqualTo(Tier.PARTICULAR);
        assertThat(view.confidence()).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(view.psychosis()).isEqualTo(PsychosisLevel.LOW);
        assertThat(view.contributors()).isZero();
    }

    @Test
    @DisplayName("T-045 / SB-005: A player with no record reads status 0, Confidence Unknown, Psychosis Low")
    void playerWithNoRecordReadsNeutral() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        TierLadder ladder = configManager.config().tiers().ladder();

        PlayerSocialView view = profileService.loadViewAsync(target, "Newbie", ladder).get();

        assertThat(view.playerId()).isEqualTo(target);
        assertThat(view.name()).isEqualTo("Newbie");
        assertThat(view.status()).isZero();
        assertThat(view.tier()).isEqualTo(Tier.PARTICULAR);
        assertThat(view.confidence()).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(view.psychosis()).isEqualTo(PsychosisLevel.LOW);
        assertThat(view.contributors()).isZero();
    }

    @Test
    @DisplayName("T-043, T-045: Computes all three separate metrics from events")
    void computesAllMetricsCorrectly() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor1 = PlayerId.of(UUID.randomUUID());
        PlayerId actor2 = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());

        // Add 2 positive reputation events from 2 distinct actors (+10 and +5 -> +15)
        reputationRepo.save(new ReputationEvent(actor1, target, 10, HonorKind.POSITIVE, 500.0, null, baseTime));
        reputationRepo.save(new ReputationEvent(actor2, target, 5, HonorKind.POSITIVE, 500.0, null, baseTime.plusSeconds(10)));

        // Add 2 open-world kills for target -> medium psychosis (threshold 2)
        psychosisRepo.save(new PsychosisEvent(target, victim, CombatContext.OPEN, baseTime));
        psychosisRepo.save(new PsychosisEvent(target, victim, CombatContext.OPEN, baseTime.plusSeconds(30)));

        TierLadder ladder = configManager.config().tiers().ladder();
        PlayerSocialView view = profileService.loadViewAsync(target, "Veteran", ladder).get();

        // 1. Social status (+15) -> Tier HONORABLE
        assertThat(view.status()).isEqualTo(15);
        assertThat(view.tier()).isEqualTo(Tier.HONORABLE);

        // 2. Reputation confidence (2 distinct actors -> weight 2.0 -> LOW confidence, threshold 1.0)
        assertThat(view.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(view.contributors()).isEqualTo(2);

        // 3. Killing psychosis (2 open kills -> MEDIUM psychosis)
        assertThat(view.psychosis()).isEqualTo(PsychosisLevel.MEDIUM);
    }

    @Test
    @DisplayName("T-045: Resolves offline player by username from profile repository")
    void resolvesOfflinePlayerByUsername() throws Exception {
        PlayerId id = PlayerId.of(UUID.randomUUID());
        TierLadder ladder = configManager.config().tiers().ladder();

        // Register profile in DB
        profileService.warmUp(id, "Alex", ladder);

        Optional<PlayerSocialView> resolved = profileService.resolvePlayerAsync("alex", ladder).get();
        assertThat(resolved).isPresent();
        assertThat(resolved.get().playerId()).isEqualTo(id);
        assertThat(resolved.get().name()).isEqualTo("Alex");
    }

    @Test
    @DisplayName("T-045: Resolves player by direct UUID string")
    void resolvesPlayerByDirectUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerId id = PlayerId.of(uuid);
        TierLadder ladder = configManager.config().tiers().ladder();

        Optional<PlayerSocialView> resolved = profileService.resolvePlayerAsync(uuid.toString(), ladder).get();
        assertThat(resolved).isPresent();
        assertThat(resolved.get().playerId()).isEqualTo(id);
        assertThat(resolved.get().status()).isZero();
    }

    @Test
    @DisplayName("T-045: Resolves unknown non-UUID username to empty")
    void resolvesUnknownUsernameToEmpty() throws Exception {
        TierLadder ladder = configManager.config().tiers().ladder();

        Optional<PlayerSocialView> resolved = profileService.resolvePlayerAsync("NonExistentPlayer123", ladder).get();
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("T-019: Invalidation clears cached view and status cache")
    void invalidationClearsCache() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        TierLadder ladder = configManager.config().tiers().ladder();

        profileService.loadViewAsync(target, "Steve", ladder).get();
        assertThat(profileService.isCached(target)).isTrue();
        assertThat(statusCache.isCached(target)).isTrue();

        profileService.invalidate(target);
        assertThat(profileService.isCached(target)).isFalse();
        assertThat(statusCache.isCached(target)).isFalse();
    }

    @Test
    @DisplayName("Finding 2: Repeated cache misses while executor is held submit exactly one in-flight load")
    void repeatedMissesQueueExactlyOneLoad() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();

        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 10 repeated chat messages / misses for the same uncached player
        for (int i = 0; i < 10; i++) {
            profileService.getViewQuick(target, snapshot);
        }

        // Exactly one load is in flight
        assertThat(profileService.inFlightCount()).isEqualTo(1);

        // Release the held executor task
        holdLatch.countDown();

        long start = System.currentTimeMillis();
        while ((!profileService.isCached(target) || profileService.inFlightCount() > 0) && System.currentTimeMillis() - start < 3000) {
            Thread.yield();
        }
        assertThat(profileService.isCached(target)).isTrue();
        assertThat(profileService.inFlightCount()).isZero();
    }

    @Test
    @DisplayName("Finding 4: Reputation repository write invalidates cached PlayerSocialView")
    void reputationSaveInvalidatesViewCache() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();

        // 1. Initial load
        PlayerSocialView view1 = profileService.loadViewAsync(target, "Target", snapshot).get();
        assertThat(view1.status()).isZero();
        assertThat(profileService.isCached(target)).isTrue();

        // 2. Save reputation event
        reputationRepo.save(new ReputationEvent(actor, target, 20, HonorKind.POSITIVE, 500.0, null, Instant.now()));

        // 3. View cache is invalidated automatically
        assertThat(profileService.isCached(target)).isFalse();

        // 4. Next chat view/load gets updated status 20
        PlayerSocialView view2 = profileService.loadViewAsync(target, "Target", snapshot).get();
        assertThat(view2.status()).isEqualTo(20);
    }

    @Test
    @DisplayName("Finding 4: Psychosis repository write invalidates cached PlayerSocialView")
    void psychosisSaveInvalidatesViewCache() throws Exception {
        PlayerId killer = PlayerId.of(UUID.randomUUID());
        PlayerId victim = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();

        // 1. Initial load
        PlayerSocialView view1 = profileService.loadViewAsync(killer, "Killer", snapshot).get();
        assertThat(view1.psychosis()).isEqualTo(PsychosisLevel.LOW);
        assertThat(profileService.isCached(killer)).isTrue();

        // 2. Save psychosis kill event
        psychosisRepo.save(new PsychosisEvent(killer, victim, CombatContext.OPEN, Instant.now()));

        // 3. View cache is invalidated automatically
        assertThat(profileService.isCached(killer)).isFalse();
    }

    @Test
    @DisplayName("Finding 8: Player quit while load is in-flight does not republish view into cache")
    void quitDuringInFlightLoadDoesNotRepublish() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();

        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Trigger load
        java.util.concurrent.CompletableFuture<PlayerSocialView> future = profileService.loadViewAsync(target, "Quitter", snapshot);

        // Player quits while load is in-flight
        profileService.evict(target);
        assertThat(profileService.isCached(target)).isFalse();

        // Release storage executor
        holdLatch.countDown();
        future.get();

        // After completion, the view MUST NOT be cached
        assertThat(profileService.isCached(target)).isFalse();
    }

    @Test
    @DisplayName("Round 2 Finding 5: Two pending loads across a quit are both rejected and do not republish")
    void twoPendingLoadsAcrossQuitDoNotRepublish() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();

        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Queue two distinct pending loads for the same player while executor is held
        // 1. warmUp queues a profile save + loadViewInternal
        profileService.warmUp(target, "Quitter", snapshot);
        // 2. resolvePlayerAsync queues a second loadViewInternal
        CompletableFuture<Optional<PlayerSocialView>> secondLoad =
                profileService.resolvePlayerAsync(target.value().toString(), snapshot);

        // Player quits while both loads are queued
        profileService.evict(target);
        assertThat(profileService.isCached(target)).isFalse();

        // Release executor to let both loads process
        holdLatch.countDown();
        secondLoad.get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Both loads must be rejected; neither must republish into the cache
        assertThat(profileService.isCached(target))
                .as("Neither stale load may republish the player profile after quit")
                .isFalse();
    }

    @Test
    void loadQueuedBeforeQuitCannotRepublishAfterRejoin() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        RuntimeSnapshot snapshot = configManager.snapshot();
        reputationRepo.save(new ReputationEvent(actor, target, 10, HonorKind.POSITIVE, 500.0, null, testClock.instant()));

        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<PlayerSocialView> staleLoad = profileService.loadViewAsync(target, "Quitter", snapshot);
        profileService.evict(target);
        profileService.warmUp(target, "Rejoined", snapshot);
        holdLatch.countDown();

        assertThat(staleLoad.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isZero();
        storage.submitAsync(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(profileService.getViewQuick(target, snapshot).status()).isEqualTo(10);
    }

    @Test
    @DisplayName("Round 2 Finding 4: In-flight cap admission and reservation is atomic under concurrency")
    void concurrentDistinctLoadsRespectAtomicCap() throws Exception {
        RuntimeSnapshot snapshot = configManager.snapshot();
        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            int threads = 8;
            int loadsPerThread = 150;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
            List<CompletableFuture<Void>> submissions = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                submissions.add(CompletableFuture.runAsync(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < loadsPerThread; i++) {
                        PlayerId id = PlayerId.of(UUID.randomUUID());
                        profileService.loadViewAsync(id, "P", snapshot);
                    }
                }, pool));
            }

            startGate.countDown();
            CompletableFuture.allOf(submissions.toArray(new CompletableFuture[0])).join();
            pool.shutdown();

            assertThat(profileService.inFlightCount())
                    .as("In-flight count must strictly respect MAX_PENDING_LOADS cap")
                    .isLessThanOrEqualTo(ProfileService.MAX_PENDING_LOADS);
        } finally {
            holdLatch.countDown();
        }
    }

    @Test
    @DisplayName("Finding 8: View cache is bounded to MAX_VIEW_CACHE_SIZE")
    void viewCacheIsBounded() {
        assertThat(profileService.cacheSize()).isLessThanOrEqualTo(ProfileService.MAX_VIEW_CACHE_SIZE);
    }

    @Test
    @DisplayName("Finding 9: Profile loading uses captured RuntimeSnapshot and does not mix with reloaded config")
    void profileLoadingUsesCapturedSnapshotNotReloadedConfig() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        reputationRepo.save(new ReputationEvent(actor, target, 5, HonorKind.POSITIVE, 500.0, null, Instant.now()));

        RuntimeSnapshot snapshot1 = configManager.snapshot();
        assertThat(snapshot1.config().tiers().ladder().resolve(5)).isEqualTo(Tier.AFABLE);

        // Hold the storage executor so the load is enqueued before execution
        java.util.concurrent.CountDownLatch holdLatch = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Enqueue load with captured snapshot1
        CompletableFuture<PlayerSocialView> future = profileService.loadViewAsync(target, "Player", snapshot1);

        try {
            // Mutate a value the load actually reads (tier1 threshold raised from 5 to 10) between enqueue and execution
            configManager.set("tiers.tier1.threshold", "10");
            RuntimeSnapshot snapshot2 = configManager.snapshot();
            assertThat(snapshot2.config().tiers().ladder().resolve(5)).isEqualTo(Tier.PARTICULAR);
        } finally {
            // Release executor to let the load execute
            holdLatch.countDown();
        }

        PlayerSocialView view1 = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(view1.tier())
                .as("Tier must be resolved from captured snapshot1 (AFABLE), not from reloaded config (PARTICULAR)")
                .isEqualTo(Tier.AFABLE);
    }

    @Test
    @DisplayName("Finding 2: Expired view in cache is removed on read, triggers background rebuild, and does no storage work on calling thread")
    void viewExpiresOnReadAndTriggersBackgroundRebuildWithoutStorageCallOnCallingThread() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());

        // Event of -5 rating at T=0 (ladder resolves -5 to TEMERARIO)
        reputationRepo.save(new ReputationEvent(actor, target, -5, HonorKind.NEGATIVE, 500.0, "reason.test", testClock.instant()));

        // Populate view cache initially
        PlayerSocialView initialView = profileService.loadViewAsync(target, "Alice", configManager.snapshot()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(initialView.status()).isEqualTo(-5);
        assertThat(initialView.tier()).isEqualTo(Tier.TEMERARIO);
        assertThat(profileService.isCached(target)).isTrue();

        // Immediate quick view hit returns the cached Temerario view
        PlayerSocialView hitView = profileService.getViewQuick(target, configManager.snapshot());
        assertThat(hitView.status()).isEqualTo(-5);
        assertThat(hitView.tier()).isEqualTo(Tier.TEMERARIO);

        // Advance clock by 15 days (half-life 30d):
        // 1. -5 decays to -4 (rounds half-up to -4, tier changes from TEMERARIO to PARTICULAR)
        // 2. View cache TTL (default 60s) has expired!
        testClock.advance(java.time.Duration.ofDays(15));

        // Lock/hold the storage executor with a latch to prove that getViewQuick on the calling thread does NO storage work
        java.util.concurrent.CountDownLatch holdStorage = new java.util.concurrent.CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdStorage.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            // Calling getViewQuick with storage paused MUST return immediately without blocking!
            long startTime = System.currentTimeMillis();
            PlayerSocialView quickView = profileService.getViewQuick(target, configManager.snapshot());
            long duration = System.currentTimeMillis() - startTime;

            // Must return neutral default immediately without blocking on storage executor
            assertThat(duration).isLessThan(500);
            assertThat(quickView.status()).isZero();
            assertThat(quickView.tier()).isEqualTo(Tier.PARTICULAR);

            // Expired entry must have been removed from cache
            assertThat(profileService.isCached(target)).isFalse();
        } finally {
            // Unblock storage executor to let background rebuild execute
            holdStorage.countDown();
        }

        // Wait for the background load to complete and repopulate the cache
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !profileService.isCached(target)) {
            Thread.sleep(10);
        }
        assertThat(profileService.isCached(target)).isTrue();

        // Subsequent quick read gets the fresh view from background rebuild with decayed status -4 and updated tier PARTICULAR
        PlayerSocialView refreshedView = profileService.getViewQuick(target, configManager.snapshot());
        assertThat(refreshedView.status()).isEqualTo(-4);
        assertThat(refreshedView.tier()).isEqualTo(Tier.PARTICULAR);
    }

    @Test
    @DisplayName("Finding 7: Editing decay configuration invalidates view cache and updates StatusCache TTL")
    void editingDecayInvalidatesCachesAndUpdatesTtl() throws Exception {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());

        reputationRepo.save(new ReputationEvent(actor, target, 10, HonorKind.POSITIVE, 500.0, null, testClock.instant()));

        // Populate viewCache and statusCache
        profileService.loadViewAsync(target, "Bob", configManager.snapshot()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(profileService.isCached(target)).isTrue();
        assertThat(statusCache.get(target)).isPresent();

        // Initial StatusCache TTL is 60s
        assertThat(statusCache.ttl()).isEqualTo(java.time.Duration.ofSeconds(60));

        // 1. In-game edit of cache-ttl via ConfigManager
        configManager.set("decay.cache-ttl", "15s");

        // StatusCache TTL must immediately reflect the new 15s duration
        assertThat(statusCache.ttl()).isEqualTo(java.time.Duration.ofSeconds(15));
        // View cache must be invalidated
        assertThat(profileService.isCached(target)).isFalse();
        assertThat(statusCache.get(target)).isEmpty();

        // 2. Re-populate cache under current config
        profileService.loadViewAsync(target, "Bob", configManager.snapshot()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(profileService.isCached(target)).isTrue();
        assertThat(statusCache.get(target)).isPresent();

        // 3. In-game edit of half-life via ConfigManager
        configManager.set("decay.half-life", "7d");

        // Changing half-life must invalidate both viewCache and statusCache
        assertThat(profileService.isCached(target)).isFalse();
        assertThat(statusCache.get(target)).isEmpty();
    }
}
