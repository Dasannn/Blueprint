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
 * - Neutral and Serenity never enter madness episodes.
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

    private void debug(RuntimeSnapshot snapshot, String message) {
        if (snapshot.config().effects().debug()) {
            java.util.logging.Logger logger = plugin != null ? plugin.getLogger()
                    : java.util.logging.Logger.getLogger(AmbientEffectScheduler.class.getName());
            logger.info("[effects] " + message);
        }
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

            var known = profileService.findViewCached(id, snapshot);
            if (known.isEmpty()) {
                debug(snapshot, player.getName() + " no cached view");
                profileService.getViewQuick(id, snapshot);
                continue;
            }
            PlayerSocialView view = known.get();
            PsychosisLevel level = view.psychosis();
            PlayerEffectState state = getOrCreateState(player.getUniqueId());
            if (state.changeDirection(level)) {
                victimReads.remove(player.getUniqueId());
                dispatcher.cancelPending(player.getUniqueId());
            }
            boolean pending = dispatcher.hasPending(player.getUniqueId());
            boolean readingVictim = victimReads.containsKey(player.getUniqueId());
            if (!state.canStartEpisode(now) || pending || readingVictim) {
                debug(snapshot, player.getName() + " level=" + level + " blocked remaining-ms="
                        + state.remainingEpisodeMillis(now) + " pending=" + pending + " victim-read=" + readingVictim);
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
                    boolean delivered = dispatcher.dispatchSerene(player, effect, snapshot,
                            () -> directionStillMatches(id, true));
                    debug(snapshot, player.getName() + " serene magnitude=" + view.psychosisMagnitude()
                            + " candidates=" + effects + " dispatch=" + effect + " delivered=" + delivered);
                    if (delivered) {
                        long ticks = SereneEpisode.reservationTicks(effect, serene, snapshot.config().sounds());
                        state.recordSerene(effect, now, ticks);
                        dispatcher.reserveEpisode(player.getUniqueId(), ticks);
                    }
                } else debug(snapshot, player.getName() + " serene magnitude=" + view.psychosisMagnitude() + " candidates=[]");
                continue;
            }
            if (!level.hasMadnessEffects() || !madnessCheck) {
                debug(snapshot, player.getName() + " level=" + level + " blocked reason="
                        + (!level.hasMadnessEffects() ? "neutral" : "check-interval"));
                continue;
            }
            List<AmbientEffectType> eligible = availableEffects(cfg, state, level, now, random);
            if (eligible.isEmpty()) {
                debug(snapshot, player.getName() + " madness level=" + level + " eligible=[] delivered=0");
                continue;
            }
            if (mainThread != null && eligible.contains(AmbientEffectType.VICTIM_GHOST)) {
                debug(snapshot, player.getName() + " madness level=" + level + " eligible=" + eligible + " victim-read=pending");
                requestVictim(player.getUniqueId(), snapshot, state, now, eligible);
            } else dispatchEpisode(player, eligible, null, snapshot, state, level, now);
        }
    }

    /** Plain-data selection: distinct ids; renderer skips do not consume limits. */
    static List<AmbientEffectType> availableEffects(EffectsConfigSection cfg, PlayerEffectState state,
                                                  PsychosisLevel level, long now, Random random) {
        List<AmbientEffectType> eligible = new ArrayList<>();
        if (!level.hasMadnessEffects()) return eligible;
        for (AmbientEffectType type : AmbientEffectType.values()) {
            if (type == AmbientEffectType.ADVANCEMENT_TOAST || level.ordinal() < type.floor().ordinal()) continue;
            var rule = cfg.presentation().rules().get(type);
            if (rule != null && !rule.allows(level)) continue;
            if (state.canFire(type, cfg.getEffect(type), now)) eligible.add(type);
        }
        Collections.shuffle(eligible, random);
        return eligible;
    }

    private void dispatchEpisode(Player player, List<AmbientEffectType> candidates, String victim,
                                 RuntimeSnapshot snapshot, PlayerEffectState state, PsychosisLevel level, long now) {
        EffectsConfigSection cfg = snapshot.config().effects();
        EffectsConfigSection scaled = cfg.scaled(level);
        UUID id = player.getUniqueId();
        int delivered = 0;
        long longestTicks = 0;
        dispatcher.cancelPending(id);
        for (AmbientEffectType type : candidates) {
            if (delivered >= cfg.presentation().maxConcurrent(level)) break;
            var rule = cfg.presentation().rules().get(type);
            if (level.ordinal() < type.floor().ordinal() || rule != null && !rule.allows(level)
                    || !state.canFire(type, cfg.getEffect(type), now)) continue;
            boolean success = type == AmbientEffectType.VICTIM_GHOST
                    ? victim != null && dispatcher.dispatchVictimGhost(player, scaled.presentation().ghost(), snapshot, victim)
                    : dispatcher.dispatch(player, type, scaled, snapshot);
            if (!success) continue;
            state.recordFired(type, now);
            delivered++;
            longestTicks = Math.max(longestTicks, type == AmbientEffectType.CREEPER_SOUND ? cfg.maxEpisodeTicks()
                    : scaled.presentation().durationTicks(type, snapshot.config().sounds()));
        }
        debug(snapshot, player.getName() + " madness level=" + level + " eligible=" + candidates + " delivered=" + delivered);
        if (delivered == 0) return;
        long quietMillis = cfg.quietInterval(level).toMillis();
        state.recordEpisode(now, longestTicks * 50L + quietMillis);
        dispatcher.reserveEpisode(id, longestTicks + (quietMillis + 49L) / 50L);
        dispatcher.guardDirection(id, () -> directionStillMatches(PlayerId.of(id), false), Math.max(1, longestTicks));
    }

    boolean directionStillMatches(PlayerId id, boolean serenity) {
        RuntimeSnapshot current = configManager.snapshot();
        var known = profileService.findViewCached(id, current);
        if (known.isEmpty()) {
            profileService.getViewQuick(id, current);
            return true;
        }
        return serenity ? known.get().psychosis() == PsychosisLevel.SERENITY
                : known.get().psychosis().hasMadnessEffects();
    }

    private void requestVictim(UUID id, RuntimeSnapshot snapshot, PlayerEffectState state, long now, List<AmbientEffectType> candidates) {
        if (mainThread == null) return;
        Object token = new Object();
        victimReads.put(id, token);
        long started = System.nanoTime();
        try {
            profileService.findVictimGhostAsync(PlayerId.of(id), snapshot).whenComplete((victim, failure) -> {
                try {
                    mainThread.execute(() -> {
                        if (!victimReads.remove(id, token)) {
                            debug(snapshot, id + " victim-read cancelled");
                            return;
                        }
                        // Reacquire the viewer here; the storage continuation captured only plain identity/config values.
                        Player viewer = onlinePlayersSupplier.get().stream().filter(p -> p.getUniqueId().equals(id) && p.isOnline())
                                .findFirst().orElse(null);
                        if (viewer == null || playerStates.get(id) != state) return;
                        long deliveredAt = now + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        PsychosisLevel level = profileService.getViewQuick(PlayerId.of(id), snapshot).psychosis();
                        if (!level.hasMadnessEffects() || !state.canStartEpisode(deliveredAt) || dispatcher.hasPending(id)) {
                            debug(snapshot, viewer.getName() + " victim-read skipped level=" + level
                                    + " remaining-ms=" + state.remainingEpisodeMillis(deliveredAt));
                            return;
                        }
                        dispatchEpisode(viewer, candidates, failure == null && victim != null && victim.isPresent()
                                ? victim.get().name() : null, snapshot, state, level, deliveredAt);
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
