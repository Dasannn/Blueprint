package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ConfidenceCalculator;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.PsychosisCalculator;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.TierLadder;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service managing player social profiles, caching, and background loading per T-042, T-045, and ARCHITECTURE.md §5.
 * - In-memory bounded cache provides fast lookups without JDBC or future waits for async chat.
 * - Uncached players return neutral defaults immediately on the chat path and schedule async fetch.
 * - Submitting to the executor takes the queue lock, but does not block on database I/O.
 * - All database access is submitted to the single-threaded storage executor.
 */
public class ProfileService {

    public static final int MAX_VIEW_CACHE_SIZE = 1000;
    public static final int MAX_PENDING_LOADS = 1000;

    private record CachedView(PlayerSocialView view, Instant expiresAt, int generation) {}

    private final StorageEngine storageEngine;
    private final ReputationRepository reputationRepository;
    private final PsychosisRepository psychosisRepository;
    private final ProfileRepository profileRepository;
    private final StatusCache statusCache;
    private final ConfigManager configManager;
    private final PlayerLookup playerLookup;
    private final Logger logger;
    private final Clock clock;

    private final Map<PlayerId, CachedView> viewCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PlayerId, CachedView> eldest) {
                    return size() > MAX_VIEW_CACHE_SIZE;
                }
            }
    );

    private final ConcurrentMap<PlayerId, CompletableFuture<PlayerSocialView>> inFlightLoads = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerId, Integer> playerGenerations = new ConcurrentHashMap<>();
    private final Set<PlayerId> evictedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<PlayerId, Long> lastQuitEpochs = new ConcurrentHashMap<>();
    private final AtomicLong quitEpoch = new AtomicLong();
    private final Object loadLock = new Object();

    public ProfileService(
            StorageEngine storageEngine,
            ReputationRepository reputationRepository,
            PsychosisRepository psychosisRepository,
            ProfileRepository profileRepository,
            StatusCache statusCache,
            ConfigManager configManager,
            PlayerLookup playerLookup,
            Logger logger,
            Clock clock
    ) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "ReputationRepository must not be null");
        this.psychosisRepository = Objects.requireNonNull(psychosisRepository, "PsychosisRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "ProfileRepository must not be null");
        this.statusCache = Objects.requireNonNull(statusCache, "StatusCache must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.playerLookup = playerLookup;
        this.logger = logger != null ? logger : Logger.getLogger(ProfileService.class.getName());
        this.clock = clock != null ? clock : Clock.systemUTC();

        // Connect repository writes directly to view cache invalidation
        this.reputationRepository.addInvalidationListener(this::invalidate);
        this.psychosisRepository.addInvalidationListener(this::invalidate);

        // Connect configuration updates to view and status cache invalidation and TTL refresh (Finding 7)
        this.configManager.addSnapshotListener(snapshot -> {
            this.invalidateAll();
            this.statusCache.updateTtl(snapshot.config().decay().cacheTtl());
        });
    }

    public ProfileService(
            StorageEngine storageEngine,
            ReputationRepository reputationRepository,
            PsychosisRepository psychosisRepository,
            ProfileRepository profileRepository,
            StatusCache statusCache,
            ConfigManager configManager,
            PlayerLookup playerLookup,
            Logger logger
    ) {
        this(storageEngine, reputationRepository, psychosisRepository, profileRepository, statusCache, configManager, playerLookup, logger, Clock.systemUTC());
    }

    /** Cache-only chat read: no executor submission, JDBC or platform lookup. */
    public PlayerSocialView getViewCached(PlayerId id, RuntimeSnapshot snapshot) {
        if (id != null) {
            synchronized (loadLock) {
                CachedView cached = viewCache.get(id);
                if (cached != null && cached.generation() == playerGenerations.getOrDefault(id, 0)
                        && !clock.instant().isAfter(cached.expiresAt())) return cached.view();
            }
        }
        return PlayerSocialView.neutral(id != null ? id : PlayerId.CONSOLE, "", snapshot.config().tiers().ladder());
    }

    /**
     * Fast profile lookup designed for {@link io.papermc.paper.event.player.AsyncChatEvent} (T-042).
     * If the profile is in the cache and unexpired, returns it immediately without waiting on JDBC or a future.
     * If absent or expired, returns the neutral default immediately and schedules an asynchronous background fetch
     * on the storage executor (submitting to the executor takes the queue lock, but does not block on database I/O).
     * Maintains exactly one in-flight load per player and bounds total pending loads.
     */
    public PlayerSocialView getViewQuick(PlayerId id, RuntimeSnapshot snapshot) {
        if (id == null) {
            return PlayerSocialView.neutral(PlayerId.of(new UUID(0, 0)), "Unknown", snapshot.config().tiers().ladder());
        }

        CachedView cached;
        synchronized (loadLock) {
            cached = viewCache.get(id);
        }

        if (cached != null) {
            Instant now = clock.instant();
            int currentGen = playerGenerations.getOrDefault(id, 0);
            if (cached.generation() == currentGen && !now.isAfter(cached.expiresAt())) {
                return cached.view();
            }
            // Expired on read or generation moved: invalidate and queue background rebuild (Finding 2)
            synchronized (loadLock) {
                viewCache.remove(id, cached);
            }
            loadViewAsync(id, cached.view().name(), snapshot);
            return PlayerSocialView.neutral(id, cached.view().name(), snapshot.config().tiers().ladder());
        }

        // Neutral default per SB-005 and T-042
        PlayerSocialView neutral = PlayerSocialView.neutral(id, id.toString(), snapshot.config().tiers().ladder());

        // Queue background fetch if not already in flight or capped
        loadViewAsync(id, id.toString(), snapshot);

        return neutral;
    }

    public PlayerSocialView getViewQuick(PlayerId id, TierLadder ladder) {
        return getViewQuick(id, configManager.snapshot());
    }

    /**
     * Computes the complete {@link PlayerSocialView} asynchronously on the storage executor.
     * Ensures only one in-flight load exists per player and bounds total pending loads.
     */
    public CompletableFuture<PlayerSocialView> loadViewAsync(PlayerId id, String fallbackName, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(snapshot, "RuntimeSnapshot must not be null");

        CompletableFuture<PlayerSocialView> existing = inFlightLoads.get(id);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<PlayerSocialView> future;
        int loadGen;
        long requestEpoch;
        synchronized (loadLock) {
            existing = inFlightLoads.get(id);
            if (existing != null) {
                return existing;
            }

            if (inFlightLoads.size() >= MAX_PENDING_LOADS) {
                return CompletableFuture.completedFuture(
                        PlayerSocialView.neutral(id, fallbackName != null ? fallbackName : id.toString(), snapshot.config().tiers().ladder())
                );
            }

            future = new CompletableFuture<>();
            inFlightLoads.put(id, future);
            loadGen = playerGenerations.getOrDefault(id, 0);
            requestEpoch = quitEpoch.get();
        }

        storageEngine.supplyAsync(() -> loadViewInternal(id, fallbackName, snapshot, loadGen, requestEpoch))
                .whenComplete((view, ex) -> {
                    synchronized (loadLock) {
                        inFlightLoads.remove(id, future);
                    }
                    if (ex != null) {
                        future.completeExceptionally(ex);
                    } else {
                        future.complete(view);
                    }
                });

        return future;
    }

    public CompletableFuture<PlayerSocialView> loadViewAsync(PlayerId id, String fallbackName, TierLadder ladder) {
        return loadViewAsync(id, fallbackName, configManager.snapshot());
    }

    private PlayerSocialView loadViewInternal(PlayerId id, String fallbackName, RuntimeSnapshot snapshot, int initialGen, long requestEpoch) {
        int expectedGen = initialGen;
        while (true) {
            synchronized (loadLock) {
                if (evictedPlayers.contains(id) || lastQuitEpochs.getOrDefault(id, 0L) > requestEpoch) {
                    Optional<PlayerProfile> profile = profileRepository.findById(id);
                    String name = profile.map(PlayerProfile::lastKnownName).orElse(fallbackName != null ? fallbackName : id.toString());
                    return PlayerSocialView.neutral(id, name, snapshot.config().tiers().ladder());
                }
                if (expectedGen < 0) {
                    expectedGen = playerGenerations.getOrDefault(id, 0);
                }
            }

            List<ReputationEvent> repEvents = reputationRepository.findByTarget(id);
            PluginConfig cfg = snapshot.config();
            Instant now = clock.instant();
            Status status = Status.fromEvents(repEvents, cfg.decay().toDomain(), now);

            ConfidenceCalculator confCalc = new ConfidenceCalculator(cfg.confidence().toDomain());
            ConfidenceLevel conf = confCalc.calculate(repEvents, now);
            int contributors = confCalc.countDistinctActors(repEvents);

            Instant windowStart = now.minus(cfg.psychosis().window());
            List<PsychosisEvent> kills = psychosisRepository.findKillsByKillerSince(id, windowStart);
            PsychosisCalculator psychCalc = new PsychosisCalculator(cfg.psychosis().toDomain());
            PsychosisLevel psych = psychCalc.calculate(id, kills, now);

            Optional<PlayerProfile> profile = profileRepository.findById(id);
            String name = profile.map(PlayerProfile::lastKnownName).orElse(fallbackName != null ? fallbackName : id.toString());

            TierLadder ladder = cfg.tiers().ladder();
            Tier tier = ladder.resolve(status.value());
            PlayerSocialView view = new PlayerSocialView(id, name, status.value(), tier, conf, psych, contributors);

            synchronized (loadLock) {
                if (evictedPlayers.contains(id) || lastQuitEpochs.getOrDefault(id, 0L) > requestEpoch) {
                    // A quit rejects every earlier load, including those that finish after rejoin.
                    return view;
                }
                int currentGen = playerGenerations.getOrDefault(id, 0);
                if (currentGen != expectedGen) {
                    // Generation moved while loading; reload to ensure fresh derived view (Finding 4)
                    expectedGen = currentGen;
                    continue;
                }

                Instant expiresAt = now.plus(cfg.decay().cacheTtl());
                viewCache.put(id, new CachedView(view, expiresAt, currentGen));
                statusCache.put(id, status, cfg.decay().toDomain());
                return view;
            }
        }
    }

    /**
     * Resolves a player by username or UUID and loads their {@link PlayerSocialView} asynchronously.
     * Resolves Bukkit identity on the calling (main) thread and passes plain values to the storage executor.
     * Works for offline players by name or UUID per T-045 and SB-065.
     * A player with no record reads status 0, Confidence Unknown, Psychosis Low (SB-005).
     */
    public record TargetIdentity(PlayerId id, String name) {
        public TargetIdentity {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * Resolves a target player identity (PlayerId and last known name) asynchronously per T-052.
     * Offline targets resolve by UUID from player_profile first, falling back to Bukkit offline lookup.
     * Bukkit identity calls happen on the calling (command) thread, never the storage executor.
     */
    public CompletableFuture<Optional<TargetIdentity>> resolveTargetIdentityAsync(String input) {
        Objects.requireNonNull(input, "input must not be null");
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // 1. Resolve online Bukkit identity on the calling (command) thread
        PlayerId onlineId = null;
        String onlineName = null;
        UUID parsedUuid = null;
        try {
            parsedUuid = UUID.fromString(trimmed);
        } catch (IllegalArgumentException ignored) {
        }

        Optional<PlayerLookup.KnownPlayer> knownOnCallingThread = (playerLookup != null)
                ? playerLookup.lookup(trimmed)
                : Optional.empty();

        if (knownOnCallingThread.isPresent() && knownOnCallingThread.get().isOnline()) {
            onlineId = knownOnCallingThread.get().id();
            onlineName = knownOnCallingThread.get().name();
        }

        final PlayerId finalOnlineId = onlineId;
        final String finalOnlineName = onlineName;
        final UUID finalUuid = parsedUuid;
        final Optional<PlayerLookup.KnownPlayer> fallbackLookup = (finalOnlineId == null)
                ? knownOnCallingThread
                : Optional.empty();

        // 2. Submit storage resolution to the database executor
        return storageEngine.supplyAsync(() -> {
            if (finalOnlineId != null) {
                return Optional.of(new TargetIdentity(finalOnlineId, finalOnlineName));
            }

            // T-052: Resolve from player_profile first
            if (finalUuid != null) {
                PlayerId id = PlayerId.of(finalUuid);
                Optional<PlayerProfile> profile = profileRepository.findById(id);
                if (profile.isPresent()) {
                    return Optional.of(new TargetIdentity(profile.get().id(), profile.get().lastKnownName()));
                }
            } else {
                Optional<PlayerProfile> profile = profileRepository.findByName(trimmed);
                if (profile.isPresent()) {
                    return Optional.of(new TargetIdentity(profile.get().id(), profile.get().lastKnownName()));
                }
            }

            // Fallback to Bukkit's offline lookup captured on the command thread
            if (fallbackLookup.isPresent()) {
                PlayerLookup.KnownPlayer fallback = fallbackLookup.get();
                return Optional.of(new TargetIdentity(fallback.id(), fallback.name()));
            }

            if (finalUuid != null) {
                return Optional.of(new TargetIdentity(PlayerId.of(finalUuid), finalUuid.toString()));
            }

            return Optional.empty();
        });
    }

    /**
     * Resolves a player by username or UUID and loads their {@link PlayerSocialView} asynchronously.
     * Resolves Bukkit identity on the calling (main) thread and passes plain values to the storage executor.
     * Works for offline players by name or UUID per T-045, T-052, and SB-065.
     * A player with no record reads status 0, Confidence Unknown, Psychosis Low (SB-005).
     */
    public CompletableFuture<Optional<PlayerSocialView>> resolvePlayerAsync(String input, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(snapshot, "RuntimeSnapshot must not be null");

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        long requestEpoch = quitEpoch.get();
        return resolveTargetIdentityAsync(trimmed)
                .thenCompose(optIdentity -> {
                    if (optIdentity.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    TargetIdentity target = optIdentity.get();
                    int gen = playerGenerations.getOrDefault(target.id(), 0);
                    return storageEngine.supplyAsync(() ->
                            Optional.of(loadViewInternal(target.id(), target.name(), snapshot, gen, requestEpoch))
                    );
                });
    }

    public CompletableFuture<Optional<PlayerSocialView>> resolvePlayerAsync(String input, TierLadder ladder) {
        return resolvePlayerAsync(input, configManager.snapshot());
    }

    /**
     * Warms up a player's profile in cache and updates their last known name on join.
     */
    public CompletableFuture<Void> warmUp(PlayerId id, String name, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(snapshot, "RuntimeSnapshot must not be null");

        int gen;
        long requestEpoch;
        synchronized (loadLock) {
            evictedPlayers.remove(id);
            gen = playerGenerations.getOrDefault(id, 0);
            requestEpoch = quitEpoch.get();
        }
        return storageEngine.submitAsync(() -> {
            Instant now = Instant.now();
            Optional<PlayerProfile> existing = profileRepository.findById(id);
            PlayerProfile profile = existing
                    .map(p -> p.withName(name, now))
                    .orElseGet(() -> PlayerProfile.create(id, name, now));
            profileRepository.save(profile);
            loadViewInternal(id, name, snapshot, gen, requestEpoch);
        }).exceptionally(error -> {
            logger.log(Level.WARNING, "Failed to warm up profile for player " + id + " (" + name + ")", error);
            return null;
        });
    }

    public CompletableFuture<Void> warmUp(PlayerId id, String name, TierLadder ladder) {
        return warmUp(id, name, configManager.snapshot());
    }

    public void invalidate(PlayerId id) {
        if (id != null) {
            synchronized (loadLock) {
                viewCache.remove(id);
                playerGenerations.compute(id, (k, g) -> (g == null ? 1 : g + 1));
            }
            statusCache.invalidate(id);
        }
    }

    public void invalidateAll() {
        synchronized (loadLock) {
            viewCache.clear();
            for (PlayerId id : playerGenerations.keySet()) {
                playerGenerations.compute(id, (k, g) -> (g == null ? 1 : g + 1));
            }
        }
        statusCache.invalidateAll();
    }

    public void evict(PlayerId id) {
        if (id != null) {
            synchronized (loadLock) {
                evictedPlayers.add(id);
                viewCache.remove(id);
                playerGenerations.compute(id, (k, g) -> (g == null ? 0 : g) + 1);
                lastQuitEpochs.put(id, quitEpoch.incrementAndGet());
            }
        }
    }

    public boolean isCached(PlayerId id) {
        if (id == null) return false;
        CachedView cached;
        synchronized (loadLock) {
            cached = viewCache.get(id);
        }
        if (cached == null) return false;
        int currentGen = playerGenerations.getOrDefault(id, 0);
        return cached.generation() == currentGen && !clock.instant().isAfter(cached.expiresAt());
    }

    public StatusCache statusCache() {
        return statusCache;
    }

    public int inFlightCount() {
        return inFlightLoads.size();
    }

    public int cacheSize() {
        return viewCache.size();
    }

    public ReputationRepository reputationRepository() {
        return reputationRepository;
    }

    /** SB-108: one executor task, existing synchronous read paths run inline on that executor. */
    public CompletableFuture<Optional<com.dasannn.socialblueprint.feature.effects.VictimGhost>>
            findVictimGhostAsync(PlayerId killer, RuntimeSnapshot snapshot) {
        return storageEngine.supplyAsync(() -> {
            Instant now = clock.instant();
            Instant since = now.minus(snapshot.config().psychosis().window());
            List<PsychosisEvent> rows = psychosisRepository.findKillsByKillerSince(killer, since);
            return com.dasannn.socialblueprint.feature.effects.VictimGhost.select(killer, rows, since, now,
                    victim -> profileRepository.findById(victim).map(PlayerProfile::lastKnownName));
        });
    }
}
