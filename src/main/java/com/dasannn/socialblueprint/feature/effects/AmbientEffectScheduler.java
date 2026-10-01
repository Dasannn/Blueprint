package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Main-thread scheduler for private Psychosis episodes (SB-096, SB-097).
 * - The lowest Psychosis level triggers nothing.
 * - Enforces independent cooldown and per-session cap per effect.
 */
public class AmbientEffectScheduler {

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final ProfileService profileService;
    private final AmbientEffectDispatcher dispatcher;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;
    private final Map<UUID, PlayerEffectState> playerStates = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private BukkitTask task;

    private static final long FIXED_TICK_INTERVAL_TICKS = 20L;
    private long lastCheckTimestamp = 0L;

    public AmbientEffectScheduler(
            Plugin plugin,
            ConfigManager configManager,
            ProfileService profileService,
            AmbientEffectDispatcher dispatcher,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this.plugin = plugin;
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.profileService = Objects.requireNonNull(profileService, "ProfileService must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "AmbientEffectDispatcher must not be null");
        this.onlinePlayersSupplier = onlinePlayersSupplier != null ? onlinePlayersSupplier : Collections::emptyList;
    }

    public synchronized void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        // The plugin's own server, not the Bukkit static: the static holder is null
        // outside a running server, and enable now reaches this path in tests.
        if (plugin != null && plugin.isEnabled() && plugin.getServer() != null) {
            task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this::tick, FIXED_TICK_INTERVAL_TICKS, FIXED_TICK_INTERVAL_TICKS);
        }
    }

    public synchronized void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }
        dispatcher.cancelAllPending();
        lastCheckTimestamp = 0L;
    }

    public void tick() {
        tickAt(System.currentTimeMillis());
    }

    public void tickAt(long now) {
        RuntimeSnapshot snapshot = configManager.snapshot();
        EffectsConfigSection cfg = snapshot.config().effects();
        long intervalMillis = cfg.checkInterval().toMillis();

        if (lastCheckTimestamp > 0 && now >= lastCheckTimestamp && (now - lastCheckTimestamp) < intervalMillis) {
            return;
        }
        lastCheckTimestamp = now;

        for (Player player : onlinePlayersSupplier.get()) {
            if (!player.isOnline()) {
                continue;
            }
            PlayerId id = PlayerId.of(player.getUniqueId());

            // The view contains only plain values. Its database load runs on the storage executor;
            // a cold cache returns LOW until loaded. Player access and dispatch stay on this main thread.
            PlayerSocialView view = profileService.getViewQuick(id, snapshot);
            PsychosisLevel level = view.psychosis();
            if (!level.hasMadnessEffects()) {
                continue;
            }

            PlayerEffectState state = getOrCreateState(player.getUniqueId());
            if (!state.canStartEpisode(now) || dispatcher.hasPending(player.getUniqueId())) {
                continue;
            }
            List<AmbientEffectType> eligible = new ArrayList<>();
            for (AmbientEffectType type : AmbientEffectType.values()) {
                if (type == AmbientEffectType.ADVANCEMENT_TOAST) continue; // No grant-free Paper delivery API.
                if (type == AmbientEffectType.SILVERFISH && level == PsychosisLevel.MEDIUM) {
                    continue;
                }
                if (cfg.presentation().rules().containsKey(type) && !cfg.presentation().rules().get(type).allows(level)) {
                    continue;
                }
                if (state.canFire(type, cfg.getEffect(type), now)) {
                    eligible.add(type);
                }
            }

            if (eligible.isEmpty()) {
                continue;
            }

            // 4. Fire one eligible effect - record only on successful dispatch (Finding 7)
            AmbientEffectType chosen = eligible.get(random.nextInt(eligible.size()));
            boolean success = dispatcher.dispatch(player, chosen, cfg, snapshot);
            if (success) {
                state.recordFired(chosen, now);
                long quietMillis = cfg.quietInterval(level).toMillis();
                long episodeTicks = chosen == AmbientEffectType.CREEPER_SOUND ? cfg.maxEpisodeTicks()
                        : cfg.presentation().durationTicks(chosen, snapshot.config().sounds());
                state.recordEpisode(now, episodeTicks * 50L + quietMillis);
                // Also retain the gate in server ticks: lag must not let a new episode
                // overlap delayed layers or consume the quiet interval.
                dispatcher.reserveEpisode(player.getUniqueId(), episodeTicks + (quietMillis + 49L) / 50L);
            }
        }
    }

    public void handlePlayerQuit(UUID playerId) {
        if (playerId != null) {
            playerStates.remove(playerId);
        }
    }

    public PlayerEffectState getOrCreateState(UUID playerId) {
        if (playerId == null) {
            return new PlayerEffectState();
        }
        return playerStates.computeIfAbsent(playerId, k -> new PlayerEffectState());
    }

    public PlayerEffectState getState(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return playerStates.get(playerId);
    }
}
