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
    private final java.util.concurrent.Executor mainThread;
    private final Map<UUID, Object> victimReads = new ConcurrentHashMap<>();
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
        this(plugin, configManager, profileService, dispatcher, onlinePlayersSupplier,
                mainThreadExecutor(plugin));
    }

    public AmbientEffectScheduler(Plugin plugin, ConfigManager configManager, ProfileService profileService,
                                  AmbientEffectDispatcher dispatcher, Supplier<Collection<? extends Player>> onlinePlayersSupplier,
                                  java.util.concurrent.Executor mainThread) {
        this.plugin = plugin;
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.profileService = Objects.requireNonNull(profileService, "ProfileService must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "AmbientEffectDispatcher must not be null");
        this.onlinePlayersSupplier = onlinePlayersSupplier != null ? onlinePlayersSupplier : Collections::emptyList;
        this.mainThread = mainThread;
    }

    private static java.util.concurrent.Executor mainThreadExecutor(Plugin plugin) {
        if (plugin == null || plugin.getServer() == null) return null;
        org.bukkit.scheduler.BukkitScheduler scheduler = plugin.getServer().getScheduler();
        return action -> scheduler.runTask(plugin, action);
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
        victimReads.clear();
        lastCheckTimestamp = 0L;
    }

    public void tick() {
        tickAt(System.currentTimeMillis());
    }

    public void tickAt(long now) {
        RuntimeSnapshot snapshot = configManager.snapshot();
        EffectsConfigSection cfg = snapshot.config().effects();
        long intervalMillis = cfg.checkInterval().toMillis();

        boolean madnessCheck = !(lastCheckTimestamp > 0 && now >= lastCheckTimestamp
                && (now - lastCheckTimestamp) < intervalMillis);
        if (madnessCheck) lastCheckTimestamp = now;

        for (Player player : onlinePlayersSupplier.get()) {
            if (!player.isOnline()) {
                continue;
            }
            PlayerId id = PlayerId.of(player.getUniqueId());

            // The view contains only plain values. Its database load runs on the storage executor;
            // a cold cache returns NEUTRAL until loaded. Player access and dispatch stay on this main thread.
            PlayerSocialView view = profileService.getViewQuick(id, snapshot);
            PsychosisLevel level = view.psychosis();
            PlayerEffectState state = getOrCreateState(player.getUniqueId());
            if (state.changeDirection(level)) {
                victimReads.remove(player.getUniqueId());
                dispatcher.cancelPending(player.getUniqueId());
            }
            if (!state.canStartEpisode(now) || dispatcher.hasPending(player.getUniqueId()) || victimReads.containsKey(player.getUniqueId())) {
                continue;
            }
            if (level == PsychosisLevel.SERENITY) {
                var serene = cfg.serenity();
                List<String> effects = serene.rules().entrySet().stream()
                        .filter(e -> SereneEpisode.allows(level, view.psychosisMagnitude(), e.getValue())
                                && state.canFireSerene(e.getKey(), e.getValue(), now))
                        .map(Map.Entry::getKey).toList();
                if (!effects.isEmpty()) {
                    String effect = effects.get(random.nextInt(effects.size()));
                    if (dispatcher.dispatchSerene(player, effect, snapshot,
                            () -> profileService.getViewQuick(id, snapshot).psychosis() == PsychosisLevel.SERENITY)) {
                        long ticks = SereneEpisode.reservationTicks(effect, serene, snapshot.config().sounds());
                        state.recordSerene(effect, now, ticks);
                        dispatcher.reserveEpisode(player.getUniqueId(), ticks);
                    }
                }
                continue;
            }
            if (!level.hasMadnessEffects() || !madnessCheck) continue;
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
            if (chosen == AmbientEffectType.VICTIM_GHOST) {
                requestVictim(player.getUniqueId(), snapshot, state, now);
                continue;
            }
            boolean success = dispatcher.dispatch(player, chosen, cfg.scaled(level), snapshot);
            if (success) {
                recordDelivery(player.getUniqueId(), chosen, snapshot, state, level, now);
            }
        }
    }

    private void recordDelivery(UUID id, AmbientEffectType chosen, RuntimeSnapshot snapshot,
                                PlayerEffectState state, PsychosisLevel level, long now) {
        EffectsConfigSection cfg = snapshot.config().effects();
        state.recordFired(chosen, now);
        long quietMillis = cfg.quietInterval(level).toMillis();
        long ticks = chosen == AmbientEffectType.CREEPER_SOUND ? cfg.maxEpisodeTicks()
                : cfg.presentation().scaled(level).durationTicks(chosen, snapshot.config().sounds());
        state.recordEpisode(now, ticks * 50L + quietMillis);
        dispatcher.reserveEpisode(id, ticks + (quietMillis + 49L) / 50L);
        dispatcher.guardDirection(id, () -> profileService.getViewQuick(PlayerId.of(id), snapshot).psychosis().hasMadnessEffects(), Math.max(1, ticks));
    }

    private void requestVictim(UUID id, RuntimeSnapshot snapshot, PlayerEffectState state, long now) {
        if (mainThread == null) return;
        Object token = new Object();
        victimReads.put(id, token);
        long started = System.nanoTime();
        try {
            profileService.findVictimGhostAsync(PlayerId.of(id), snapshot).whenComplete((victim, failure) -> {
                try {
                    mainThread.execute(() -> {
                        if (!victimReads.remove(id, token) || failure != null || victim.isEmpty()) return;
                        // Reacquire the viewer here; the storage continuation captured only plain identity/config values.
                        Player viewer = onlinePlayersSupplier.get().stream().filter(p -> p.getUniqueId().equals(id) && p.isOnline())
                                .findFirst().orElse(null);
                        if (viewer == null || playerStates.get(id) != state) return;
                        long deliveredAt = now + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        PsychosisLevel level = profileService.getViewQuick(PlayerId.of(id), snapshot).psychosis();
                        EffectsConfigSection cfg = snapshot.config().effects();
                        if (!cfg.presentation().rules().get(AmbientEffectType.VICTIM_GHOST).allows(level)
                                || !state.canStartEpisode(deliveredAt) || dispatcher.hasPending(id)
                                || !state.canFire(AmbientEffectType.VICTIM_GHOST, cfg.getEffect(AmbientEffectType.VICTIM_GHOST), deliveredAt)) return;
                        if (dispatcher.dispatchVictimGhost(viewer, cfg.presentation().scaled(level).ghost(), snapshot, victim.get().name()))
                            recordDelivery(id, AmbientEffectType.VICTIM_GHOST, snapshot, state, level, deliveredAt);
                    });
                } catch (RuntimeException stopped) { victimReads.remove(id, token); }
            });
        } catch (RuntimeException stopped) { victimReads.remove(id, token); }
    }

    public void handlePlayerWorldChange(UUID playerId) { victimReads.remove(playerId); }

    public void handlePlayerQuit(UUID playerId) {
        if (playerId != null) {
            victimReads.remove(playerId);
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
