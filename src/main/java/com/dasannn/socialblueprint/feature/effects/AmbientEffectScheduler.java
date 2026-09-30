package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.Bukkit;
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
 * Main-thread scheduler for low-status ambient effects per T-070, SB-040, and SB-043.
 * - Fires below a configurable status threshold.
 * - Enforces independent cooldown and per-session cap per effect.
 * - Skips players who have opted out (SB-044).
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
        if (plugin != null && plugin.isEnabled()) {
            long intervalTicks = Math.max(20L, configManager.snapshot().config().effects().checkInterval().toSeconds() * 20L);
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
        }
    }

    public synchronized void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }
    }

    public void tick() {
        tickAt(System.currentTimeMillis());
    }

    public void tickAt(long now) {
        RuntimeSnapshot snapshot = configManager.snapshot();
        EffectsConfigSection cfg = snapshot.config().effects();
        int threshold = cfg.threshold();

        for (Player player : onlinePlayersSupplier.get()) {
            if (!player.isOnline()) {
                continue;
            }
            PlayerId id = PlayerId.of(player.getUniqueId());

            // 1. Opt-out check (SB-044)
            if (profileService.isEffectsOptedOut(id)) {
                continue;
            }

            // 2. Below status threshold check (SB-040)
            PlayerSocialView view = profileService.getViewQuick(id, snapshot);
            if (view.status() >= threshold) {
                continue;
            }

            // 3. Rate limiting and session cap per effect (SB-043)
            PlayerEffectState state = getOrCreateState(player.getUniqueId());
            List<AmbientEffectType> eligible = new ArrayList<>();
            for (AmbientEffectType type : AmbientEffectType.values()) {
                if (state.canFire(type, cfg.getEffect(type), now)) {
                    eligible.add(type);
                }
            }

            if (eligible.isEmpty()) {
                continue;
            }

            // 4. Fire one eligible effect
            AmbientEffectType chosen = eligible.get(random.nextInt(eligible.size()));
            state.recordFired(chosen, now);
            dispatcher.dispatch(player, chosen, cfg, snapshot);
        }
    }

    public void handlePlayerQuit(UUID playerId) {
        playerStates.remove(playerId);
    }

    public PlayerEffectState getOrCreateState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, k -> new PlayerEffectState());
    }

    public PlayerEffectState getState(UUID playerId) {
        return playerStates.get(playerId);
    }
}
