package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.PluginConfig;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Service managing player social profiles, caching, and background loading per T-042, T-045, and ARCHITECTURE.md §5.
 * - In-memory cache provides non-blocking lookups for async chat.
 * - Uncached players return neutral defaults immediately on the chat path and schedule async fetch.
 * - All database access is submitted to the single-threaded storage executor.
 */
public class ProfileService {

    private final StorageEngine storageEngine;
    private final ReputationRepository reputationRepository;
    private final PsychosisRepository psychosisRepository;
    private final ProfileRepository profileRepository;
    private final StatusCache statusCache;
    private final ConfigManager configManager;
    private final PlayerLookup playerLookup;
    private final Logger logger;

    private final ConcurrentMap<PlayerId, PlayerSocialView> viewCache = new ConcurrentHashMap<>();

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
    }

    /**
     * Non-blocking profile lookup designed for {@link io.papermc.paper.event.player.AsyncChatEvent} (T-042).
     * If the profile is in the cache, returns it immediately.
     * If absent, returns the neutral default immediately and schedules an asynchronous background fetch
     * on the storage executor without blocking the caller.
     */
    public PlayerSocialView getViewQuick(PlayerId id, TierLadder ladder) {
        if (id == null) {
            return PlayerSocialView.neutral(PlayerId.of(new UUID(0, 0)), "Unknown", ladder);
        }

        PlayerSocialView cached = viewCache.get(id);
        if (cached != null) {
            return cached;
        }

        // Neutral default per SB-005 and T-042
        PlayerSocialView neutral = PlayerSocialView.neutral(id, id.toString(), ladder);

        // Queue background fetch so future messages use the persisted profile
        loadViewAsync(id, id.toString(), ladder);

        return neutral;
    }

    /**
     * Computes the complete {@link PlayerSocialView} asynchronously on the storage executor.
     */
    public CompletableFuture<PlayerSocialView> loadViewAsync(PlayerId id, String fallbackName, TierLadder ladder) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(ladder, "TierLadder must not be null");

        return storageEngine.supplyAsync(() -> loadViewInternal(id, fallbackName, ladder));
    }

    private PlayerSocialView loadViewInternal(PlayerId id, String fallbackName, TierLadder ladder) {
        List<ReputationEvent> repEvents = reputationRepository.findByTarget(id);
        Status status = Status.fromEvents(repEvents);

        PluginConfig cfg = configManager.config();
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

        Tier tier = ladder.resolve(status.value());
        PlayerSocialView view = new PlayerSocialView(id, name, status.value(), tier, conf, psych, contributors);

        viewCache.put(id, view);
        statusCache.put(id, status);
        return view;
    }

    /**
     * Resolves a player by username or UUID and loads their {@link PlayerSocialView} asynchronously.
     * Works for offline players by name or UUID per T-045 and SB-065.
     * A player with no record reads status 0, Confidence Unknown, Psychosis Low (SB-005).
     */
    public CompletableFuture<Optional<PlayerSocialView>> resolvePlayerAsync(String input, TierLadder ladder) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(ladder, "TierLadder must not be null");

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return storageEngine.supplyAsync(() -> {
            // 1. Try resolving via PlayerLookup if available
            if (playerLookup != null) {
                Optional<PlayerLookup.KnownPlayer> known = playerLookup.lookup(trimmed);
                if (known.isPresent()) {
                    PlayerLookup.KnownPlayer player = known.get();
                    return Optional.of(loadViewInternal(player.id(), player.name(), ladder));
                }
            }

            // 2. Try parsing direct UUID
            try {
                UUID uuid = UUID.fromString(trimmed);
                PlayerId playerId = PlayerId.of(uuid);
                return Optional.of(loadViewInternal(playerId, trimmed, ladder));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID
            }

            // 3. Fallback: check profileRepository by username on storage executor
            Optional<PlayerProfile> profile = profileRepository.findByName(trimmed);
            if (profile.isPresent()) {
                PlayerProfile p = profile.get();
                return Optional.of(loadViewInternal(p.id(), p.lastKnownName(), ladder));
            }

            // Not found anywhere
            return Optional.empty();
        });
    }

    /**
     * Warms up a player's profile in cache and updates their last known name on join.
     */
    public void warmUp(PlayerId id, String name, TierLadder ladder) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(ladder, "TierLadder must not be null");

        storageEngine.submitAsync(() -> {
            Instant now = Instant.now();
            PlayerProfile profile = PlayerProfile.create(id, name, now);
            profileRepository.save(profile);
            loadViewInternal(id, name, ladder);
        });
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
        }
    }

    public boolean isCached(PlayerId id) {
        return id != null && viewCache.containsKey(id);
    }

    public StatusCache statusCache() {
        return statusCache;
    }
}
