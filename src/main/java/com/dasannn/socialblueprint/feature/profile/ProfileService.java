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

    private final StorageEngine storageEngine;
    private final ReputationRepository reputationRepository;
    private final PsychosisRepository psychosisRepository;
    private final ProfileRepository profileRepository;
    private final StatusCache statusCache;
    private final ConfigManager configManager;
    private final PlayerLookup playerLookup;
    private final Logger logger;

    private final Map<PlayerId, PlayerSocialView> viewCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PlayerId, PlayerSocialView> eldest) {
                    return size() > MAX_VIEW_CACHE_SIZE;
                }
            }
    );

    private final ConcurrentMap<PlayerId, CompletableFuture<PlayerSocialView>> inFlightLoads = new ConcurrentHashMap<>();
    private final Set<PlayerId> evictedPlayers = ConcurrentHashMap.newKeySet();

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
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "ReputationRepository must not be null");
        this.psychosisRepository = Objects.requireNonNull(psychosisRepository, "PsychosisRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "ProfileRepository must not be null");
        this.statusCache = Objects.requireNonNull(statusCache, "StatusCache must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.playerLookup = playerLookup;
        this.logger = logger != null ? logger : Logger.getLogger(ProfileService.class.getName());

        // Connect repository writes directly to view cache invalidation
        this.reputationRepository.addInvalidationListener(this::invalidate);
        this.psychosisRepository.addInvalidationListener(this::invalidate);
    }

    /**
     * Fast profile lookup designed for {@link io.papermc.paper.event.player.AsyncChatEvent} (T-042).
     * If the profile is in the cache, returns it immediately without waiting on JDBC or a future.
     * If absent, returns the neutral default immediately and schedules an asynchronous background fetch
     * on the storage executor (submitting to the executor takes the queue lock, but does not block on database I/O).
     * Maintains exactly one in-flight load per player and bounds total pending loads.
     */
    public PlayerSocialView getViewQuick(PlayerId id, RuntimeSnapshot snapshot) {
        if (id == null) {
            return PlayerSocialView.neutral(PlayerId.of(new UUID(0, 0)), "Unknown", snapshot.config().tiers().ladder());
        }

        PlayerSocialView cached = viewCache.get(id);
        if (cached != null) {
            return cached;
        }

        // Neutral default per SB-005 and T-042
        PlayerSocialView neutral = PlayerSocialView.neutral(id, id.toString(), snapshot.config().tiers().ladder());

        // Queue background fetch only if not already in flight and pending queue is not full
        if (!inFlightLoads.containsKey(id) && inFlightLoads.size() < MAX_PENDING_LOADS) {
            loadViewAsync(id, id.toString(), snapshot);
        }

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

        if (inFlightLoads.size() >= MAX_PENDING_LOADS) {
            return CompletableFuture.completedFuture(
                    PlayerSocialView.neutral(id, fallbackName != null ? fallbackName : id.toString(), snapshot.config().tiers().ladder())
            );
        }

        CompletableFuture<PlayerSocialView> future = new CompletableFuture<>();
        CompletableFuture<PlayerSocialView> previous = inFlightLoads.putIfAbsent(id, future);
        if (previous != null) {
            return previous;
        }

        storageEngine.supplyAsync(() -> loadViewInternal(id, fallbackName, snapshot))
                .whenComplete((view, ex) -> {
                    inFlightLoads.remove(id, future);
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

    private PlayerSocialView loadViewInternal(PlayerId id, String fallbackName, RuntimeSnapshot snapshot) {
        List<ReputationEvent> repEvents = reputationRepository.findByTarget(id);
        Status status = Status.fromEvents(repEvents);

        PluginConfig cfg = snapshot.config();
        ConfidenceCalculator confCalc = new ConfidenceCalculator(cfg.confidence().toDomain());
        Instant now = Instant.now();
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

        // If the player quit while this load was in-flight, do not republish into viewCache
        boolean wasEvicted = evictedPlayers.remove(id);
        if (!wasEvicted) {
            viewCache.put(id, view);
        }
        statusCache.put(id, status);
        return view;
    }

    /**
     * Resolves a player by username or UUID and loads their {@link PlayerSocialView} asynchronously.
     * Resolves Bukkit identity on the calling (main) thread and passes plain values to the storage executor.
     * Works for offline players by name or UUID per T-045 and SB-065.
     * A player with no record reads status 0, Confidence Unknown, Psychosis Low (SB-005).
     */
    public CompletableFuture<Optional<PlayerSocialView>> resolvePlayerAsync(String input, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(snapshot, "RuntimeSnapshot must not be null");

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // 1. Resolve Bukkit identity on the calling thread (main thread) per ARCHITECTURE.md §5
        PlayerId resolvedId = null;
        String resolvedName = null;

        if (playerLookup != null) {
            Optional<PlayerLookup.KnownPlayer> known = playerLookup.lookup(trimmed);
            if (known.isPresent()) {
                resolvedId = known.get().id();
                resolvedName = known.get().name();
            }
        }

        if (resolvedId == null) {
            try {
                UUID uuid = UUID.fromString(trimmed);
                resolvedId = PlayerId.of(uuid);
                resolvedName = trimmed;
            } catch (IllegalArgumentException ignored) {
                // Not a UUID string
            }
        }

        final PlayerId targetId = resolvedId;
        final String targetName = resolvedName;

        // 2. Submit database work to the storage executor with plain values (no Bukkit calls off-thread)
        return storageEngine.supplyAsync(() -> {
            if (targetId != null) {
                return Optional.of(loadViewInternal(targetId, targetName, snapshot));
            }

            // Fallback for offline player by username: query profileRepository on storage executor
            Optional<PlayerProfile> profile = profileRepository.findByName(trimmed);
            if (profile.isPresent()) {
                PlayerProfile p = profile.get();
                return Optional.of(loadViewInternal(p.id(), p.lastKnownName(), snapshot));
            }

            return Optional.empty();
        });
    }

    public CompletableFuture<Optional<PlayerSocialView>> resolvePlayerAsync(String input, TierLadder ladder) {
        return resolvePlayerAsync(input, configManager.snapshot());
    }

    /**
     * Warms up a player's profile in cache and updates their last known name on join.
     */
    public void warmUp(PlayerId id, String name, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(snapshot, "RuntimeSnapshot must not be null");

        evictedPlayers.remove(id);
        storageEngine.submitAsync(() -> {
            Instant now = Instant.now();
            PlayerProfile profile = PlayerProfile.create(id, name, now);
            profileRepository.save(profile);
            loadViewInternal(id, name, snapshot);
        });
    }

    public void warmUp(PlayerId id, String name, TierLadder ladder) {
        warmUp(id, name, configManager.snapshot());
    }

    public void invalidate(PlayerId id) {
        if (id != null) {
            viewCache.remove(id);
            statusCache.invalidate(id);
        }
    }

    public void invalidateAll() {
        viewCache.clear();
        statusCache.invalidateAll();
    }

    public void evict(PlayerId id) {
        if (id != null) {
            viewCache.remove(id);
            evictedPlayers.add(id);
        }
    }

    public boolean isCached(PlayerId id) {
        return id != null && viewCache.containsKey(id);
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
}
