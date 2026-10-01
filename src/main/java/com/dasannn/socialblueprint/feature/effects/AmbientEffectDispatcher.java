package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SoundLayerConfig;
import com.dasannn.socialblueprint.config.SoundSlotConfig;
import com.dasannn.socialblueprint.config.PresentationConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import com.dasannn.socialblueprint.config.CatalogueLines;
import com.dasannn.socialblueprint.config.ConfigValidationException;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches private Psychosis effects (SB-041, SB-092, SB-098, SB-099).
 * Absolutely private: no broadcast, no server logging, no leakage to other players.
 */
public class AmbientEffectDispatcher {

    @FunctionalInterface
    public interface SoundScheduler {
        interface TaskHandle {
            void cancel();
        }

        TaskHandle schedule(Runnable task, long delayTicks);
    }

    @FunctionalInterface
    public interface SoundPlayer {
        void play(Player player, SoundLayerConfig layer);
    }

    private final MessageRegistry messageRegistry;
    private final FakeSilverfishService silverfishService;
    private final SoundScheduler scheduler;
    private final SoundPlayer soundPlayer;
    private final Map<UUID, List<SoundScheduler.TaskHandle>> pendingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Runnable> activeSoundStops = new ConcurrentHashMap<>();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final java.util.logging.Logger logger;
    private final Random random = new Random();

    public AmbientEffectDispatcher(
            Plugin plugin,
            MessageRegistry messageRegistry,
            ConfigManager configManager,
            FakeSilverfishService silverfishService
    ) {
        this(plugin, messageRegistry, configManager, silverfishService, defaultScheduler(plugin), defaultSoundPlayer());
    }

    public AmbientEffectDispatcher(
            Plugin plugin,
            MessageRegistry messageRegistry,
            ConfigManager configManager,
            FakeSilverfishService silverfishService,
            SoundScheduler scheduler
    ) {
        this(plugin, messageRegistry, configManager, silverfishService, scheduler, defaultSoundPlayer());
    }

    public AmbientEffectDispatcher(
            Plugin plugin,
            MessageRegistry messageRegistry,
            ConfigManager configManager,
            FakeSilverfishService silverfishService,
            SoundScheduler scheduler,
            SoundPlayer soundPlayer
    ) {
        this.logger = plugin == null ? java.util.logging.Logger.getLogger(getClass().getName()) : plugin.getLogger();
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "MessageRegistry must not be null");
        this.silverfishService = Objects.requireNonNull(silverfishService, "FakeSilverfishService must not be null");
        this.scheduler = scheduler != null ? scheduler : defaultScheduler(plugin);
        this.soundPlayer = soundPlayer != null ? soundPlayer : defaultSoundPlayer();
    }

    private static SoundScheduler defaultScheduler(Plugin plugin) {
        return (task, delay) -> {
            if (plugin != null && plugin.isEnabled()) {
                org.bukkit.scheduler.BukkitTask bt = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
                return bt::cancel;
            }
            return null;
        };
    }

    private static SoundPlayer defaultSoundPlayer() {
        return (player, layer) -> {
            // player.playSound sends the sound packet ONLY to this player (private per SB-041, T-137)
            // String overload per T-135 so it can be verified in unit tests without registry
            player.playSound(player.getLocation(), layer.key(), layer.category(), layer.volume(), layer.pitch());
        };
    }

    public boolean dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(config, "config must not be null");

        cancelPending(player.getUniqueId()); // interruption restores the previous owned presentation first
        return switch (type) {
            case ADVANCEMENT_TOAST -> {
                List<String> keys = snapshot.messages().lineKeys("effects.advancement-toast.lines");
                yield !keys.isEmpty() && ToastDecision.describe(keys.getFirst(), config.presentation().toast()).deliveryAvailable();
            }
            case BOSS_BAR -> dispatchBar(player, config.presentation().bar(), snapshot);
            case FALSE_DEATH -> dispatchFalseDeath(player, config.presentation().deathRange(), snapshot);
            case SKY -> dispatchSky(player, config.presentation().sky());
            case PARTICLES -> dispatchParticles(player, config.presentation().particles());
            case SCREEN_FLASH -> dispatchScreen(player, config.presentation().flash(), snapshot);
            case SOURCE_LESS_SOUNDS -> dispatchSourceLess(player, config.presentation(), snapshot);
            case BLOCK_CHANGE -> dispatchBlock(player, config.presentation().block(), false, snapshot);
            case SIGN -> dispatchBlock(player, config.presentation().sign(), true, snapshot);
            case HURT_FLASH -> dispatchHurt(player, config.presentation(), snapshot);
            case VICTIM_GHOST -> false; // Requires the asynchronous killer-history read before rendering.
            case SILVERFISH -> dispatchSilverfish(player);
            case WHISPER -> dispatchWhisper(player, snapshot);
            case CREEPER_SOUND -> {
                dispatchCreeperSound(player, config, snapshot);
                yield true;
            }
            case FAKE_ANNOUNCEMENT -> dispatchFakeAnnouncement(player, snapshot);
        };
    }

    ActivePresentationEntry startPresentation(UUID playerId, AmbientEffectType type, long ticks, Runnable restore) {
        AmbientEntityRegistry registry = silverfishService.registry();
        ActivePresentationEntry entry = new ActivePresentationEntry(playerId, type, ticks, restore);
        registry.registerPresentation(entry);
        try {
            if (scheduleTracked(entry.playerId(), () -> registry.cleanPresentation(entry), ticks)) return entry;
        } catch (RuntimeException failure) {
            registry.cleanPresentation(entry);
            throw failure;
        }
        registry.cleanPresentation(entry);
        return null; // Never change the client without a scheduled restoration.
    }

    private boolean dispatchBlock(Player player, PresentationConfig.Block config, boolean sign, RuntimeSnapshot snapshot) {
        org.bukkit.block.data.BlockData fake = player.getServer().createBlockData(config.data());
        org.bukkit.block.Block block = PrivateBlocks.choose(player, config, fake, sign, random);
        if (block == null) return false;
        List<Component> lines = new java.util.ArrayList<>();
        if (sign) {
            for (String key : snapshot.messages().lineKeys("effects.sign.lines"))
                lines.add(messageRegistry.render(snapshot, key, Map.of()));
            if (lines.isEmpty()) return false;
            while (lines.size() < 4) lines.add(Component.empty());
        }
        Location at = block.getLocation();
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), sign ? AmbientEffectType.SIGN : AmbientEffectType.BLOCK_CHANGE,
                config.durationTicks(), () -> PrivateBlocks.restore(player, at));
        if (entry == null) return false;
        try {
            player.sendBlockChange(at, fake);
            if (sign) player.sendSignChange(at, lines);
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchHurt(Player player, PresentationConfig config, RuntimeSnapshot snapshot) {
        PresentationConfig.Hurt hurt = config.hurt();
        SoundSlotConfig slot = snapshot.config().sounds().get(hurt.slot());
        if (slot.isSilent()) return false;
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.HURT_FLASH,
                config.durationTicks(AmbientEffectType.HURT_FLASH, snapshot.config().sounds()), () -> stopLayers(player, slot));
        if (entry == null) return false;
        try {
            long audioTicks = slot.layers().stream().filter(layer -> !layer.isSilent())
                    .mapToLong(SoundLayerConfig::delay).max().orElse(0) + hurt.playbackTicks();
            if (audioTicks < hurt.animationTicks()
                    && !scheduleTracked(player.getUniqueId(), () -> stopLayers(player, slot), audioTicks)) {
                silverfishService.registry().cleanPresentation(entry);
                return false;
            }
            player.sendHurtAnimation(hurt.yaw());
            playSoundSlot(player, slot, hurt.slot(), snapshot);
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    public boolean dispatchVictimGhost(Player player, PresentationConfig.Ghost config, RuntimeSnapshot snapshot, String name) {
        Component label = messageRegistry.render(snapshot, "effects.victim-ghost.label", Map.of("victim", name));
        if (label.equals(Component.empty())) return false;
        double yaw = Math.toRadians(player.getLocation().getYaw());
        double distance = Math.min(config.range() / 2, 3);
        double height = Math.min(config.range() / 2, 1.5);
        Location at = player.getLocation().clone().add(-Math.sin(yaw) * distance, height, Math.cos(yaw) * distance);
        PrivateGhost ghost;
        try {
            ghost = new PrivateGhost(player, at, label,
                    silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft("text_display")));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (warnedKeys.add("ghost-bridge")) java.util.logging.Logger.getLogger(getClass().getName())
                    .warning("Private ghost bridge unavailable; skipping victim ghosts: " + failure.getMessage());
            return false;
        }
        cancelPending(player.getUniqueId());
        AmbientEntityRegistry registry = silverfishService.registry();
        registry.register(ghost.entry());
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.VICTIM_GHOST,
                config.durationTicks(), () -> registry.cleanDespawn(ghost.entry()));
        if (entry == null) return false;
        try { ghost.show(); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) {
            registry.cleanPresentation(entry);
            return false;
        }
    }

    public void restoreBlocks(UUID playerId) {
        AmbientEntityRegistry registry = silverfishService.registry();
        for (ActivePresentationEntry entry : registry.presentationsFor(playerId))
            if (entry.type() == AmbientEffectType.BLOCK_CHANGE || entry.type() == AmbientEffectType.SIGN)
                registry.cleanPresentation(entry);
    }

    private boolean dispatchSky(Player player, PresentationConfig.Sky config) {
        org.bukkit.WeatherType previousWeather = player.getPlayerWeather();
        UUID worldId = player.getWorld().getUID();
        boolean night = config.mode().equals("night");
        SkyPresentation sky = new SkyPresentation(player.getPlayerTimeOffset(), player.isPlayerTimeRelative(),
                previousWeather == null ? null : previousWeather.name(), 18000L, false, "DOWNFALL", night, !night);
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.SKY, config.durationTicks(), () -> {
            if (sky.ownsTime(player.getPlayerTimeOffset(), player.isPlayerTimeRelative())) {
                if (sky.resetTime(player.getWorld().getUID().equals(worldId))) player.resetPlayerTime();
                else player.setPlayerTime(sky.previousTimeOffset(), sky.previousTimeRelative());
            }
            org.bukkit.WeatherType currentWeather = player.getPlayerWeather();
            if (sky.ownsWeather(currentWeather == null ? null : currentWeather.name())) {
                if (sky.resetWeather(player.getWorld().getUID().equals(worldId))) player.resetPlayerWeather();
                else player.setPlayerWeather(previousWeather);
            }
        });
        if (entry == null) return false;
        try {
            if (night) player.setPlayerTime(18000L, false);
            else player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchParticles(Player player, PresentationConfig.Particles config) {
        org.bukkit.Particle particle = org.bukkit.Particle.valueOf(config.type().toUpperCase(java.util.Locale.ROOT));
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.PARTICLES, config.totalTicks(), () -> {});
        if (entry == null) return false;
        try {
            Location origin = player.getLocation();
            for (int i = 0; i < config.count(); i++) {
                ParticlePoint point = ParticlePoint.at(config, i);
                player.spawnParticle(particle, origin.clone().add(point.x(), point.y(), point.z()), 1, 0, 0, 0, 0);
            }
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchScreen(Player player, PresentationConfig.Flash config, RuntimeSnapshot snapshot) {
        List<String> keys = snapshot.messages().lineKeys("effects.screen-flash.lines");
        if (keys.isEmpty()) return false;
        String key = keys.get(random.nextInt(keys.size()));
        Component text = messageRegistry.render(snapshot, key, Map.of());
        PrivateScreen screen;
        try { screen = new PrivateScreen(player, text, config); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            if (warnedKeys.add("screen-bridge")) java.util.logging.Logger.getLogger(getClass().getName())
                    .warning("Private screen bridge unavailable; skipping screen flashes: " + failure.getMessage());
            return false;
        }
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.SCREEN_FLASH, config.totalTicks(), screen::restore);
        if (entry == null) return false;
        try { screen.show(); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchSourceLess(Player player, PresentationConfig config, RuntimeSnapshot snapshot) {
        PresentationConfig.Sounds settings = config.sounds();
        SoundSlotConfig slot = snapshot.config().sounds().get(settings.slot());
        if (slot.isSilent()) return false;
        long ticks = config.durationTicks(AmbientEffectType.SOURCE_LESS_SOUNDS, snapshot.config().sounds());
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.SOURCE_LESS_SOUNDS, ticks,
                () -> stopLayers(player, slot));
        if (entry == null) return false;
        // Reuse the same layered player/scheduler; only its recipient-relative location differs.
        playSoundSlot(player, slot, settings.slot(), snapshot, (recipient, layer) -> {
            Location origin = recipient.getLocation();
            double yaw = Math.toRadians(origin.getYaw());
            Location at = origin.clone().add(-Math.sin(yaw) * settings.forward() - Math.cos(yaw) * settings.right(),
                    settings.up(), Math.cos(yaw) * settings.forward() - Math.sin(yaw) * settings.right());
            recipient.playSound(at, layer.key(), layer.category(), layer.volume(), layer.pitch());
        });
        return true;
    }

    private boolean dispatchSilverfish(Player player) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 1.5 + random.nextDouble() * 2.0;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;

        Location at = player.getLocation().clone().add(dx, 0, dz);
        ActiveEntityEntry entry = silverfishService.spawnSilverfish(player, at);
        return entry != null;
    }

    private boolean dispatchWhisper(Player player, RuntimeSnapshot snapshot) {
        List<String> keys = new java.util.ArrayList<>(snapshot.messages().lineKeys("effects.private-chat.lines"));
        if (keys.isEmpty()) for (int i = 1; i <= 3; i++) keys.add("effects.whisper-" + i);
        keys.addAll(snapshot.messages().lineKeys(CatalogueLines.CUSTOM));
        Map<String, String> values = Map.of("player", player.getName());
        keys.removeIf(key -> !validLine(snapshot, key, values, snapshot.config().effects().presentation().maxVisibleLength()));
        if (keys.isEmpty()) return false;
        String key = keys.get(random.nextInt(keys.size()));
        player.sendMessage(messageRegistry.render(snapshot, key, values));
        return true;
    }

    private boolean validLine(RuntimeSnapshot snapshot, String key, Map<String, String> values, int max) {
        String base = key;
        int index = -1;
        for (String list : CatalogueLines.LISTS) if (key.startsWith(list + ".")) {
            base = list;
            index = Integer.parseInt(key.substring(list.length() + 1));
            break;
        }
        try {
            CatalogueLines.validateLine(base, index, messageRegistry.getRaw(snapshot, key), values, max, base.equals(CatalogueLines.CUSTOM));
            return true;
        } catch (ConfigValidationException error) {
            if (warnedKeys.add(error.getMessage())) logger.warning(error.getMessage());
            return false;
        }
    }

    private boolean dispatchBar(Player player, PresentationConfig.Bar config, RuntimeSnapshot snapshot) {
        List<String> keys = snapshot.messages().lineKeys("effects.boss-bar.lines");
        if (keys.isEmpty()) return false;
        String key = keys.get(random.nextInt(keys.size()));
        Map<String, String> values = Map.of("player", player.getName());
        if (!validLine(snapshot, key, values, 160)) return false;
        BossBar bar = BossBar.bossBar(messageRegistry.render(snapshot, key, values), (float) config.progress(),
                BossBar.Color.valueOf(config.colour().toUpperCase(java.util.Locale.ROOT)),
                BossBar.Overlay.valueOf(config.style().toUpperCase(java.util.Locale.ROOT)));
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.BOSS_BAR,
                config.durationTicks(), () -> player.hideBossBar(bar));
        if (entry == null) return false;
        try {
            player.showBossBar(bar);
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchFalseDeath(Player player, double range, RuntimeSnapshot snapshot) {
        Location origin = player.getLocation();
        List<FalseDeathTarget> candidates = new java.util.ArrayList<>();
        for (Player subject : player.getWorld().getPlayers()) {
            boolean sameWorld = subject.getWorld().equals(player.getWorld());
            // Skip any explicit vanish marker, regardless of other plugins' metadata conventions.
            candidates.add(new FalseDeathTarget(subject.getUniqueId(), subject.getName(), sameWorld,
                    player.canSee(subject), subject.isOnline() && !subject.isDead(), subject.hasMetadata("vanished"),
                    sameWorld ? origin.distanceSquared(subject.getLocation()) : Double.POSITIVE_INFINITY));
        }
        List<FalseDeathTarget> eligible = FalseDeathTarget.eligible(player.getUniqueId(), candidates, range);
        if (eligible.isEmpty()) return false;
        FalseDeathTarget subject = eligible.get(random.nextInt(eligible.size()));
        Map<String, String> values = Map.of("player", subject.name());
        String key = "effects.false-death.line";
        if (!validLine(snapshot, key, values, 160)) return false;
        player.sendMessage(messageRegistry.render(snapshot, key, values));
        return true;
    }

    private void dispatchCreeperSound(Player player, EffectsConfigSection config, RuntimeSnapshot snapshot) {
        SoundSlotConfig slot = snapshot.config().sounds().get("creeper-fuse");
        int bound = config.maxEpisodeTicks();
        SoundSlotConfig bounded = new SoundSlotConfig(slot.layers().stream()
                .filter(layer -> layer.delay() < bound).toList());
        playSoundSlot(player, bounded, "creeper-fuse", snapshot);
        if (!bounded.isSilent()) {
            UUID id = player.getUniqueId();
            Runnable stop = () -> stopLayers(player, bounded);
            activeSoundStops.put(id, stop);
            scheduleTracked(id, () -> {
                if (activeSoundStops.remove(id, stop)) stop.run();
            }, bound);
        }
    }

    /**
     * Plays a named sound slot dynamically from snapshot at play time per SB-091, SB-092, and T-138.
     * Supports single-mapping slots (delay 0) and layered slots with individual delays.
     */
    public void playSoundSlot(Player player, String slotName, RuntimeSnapshot snapshot) {
        if (player == null || slotName == null || slotName.isBlank()) {
            return;
        }
        SoundSlotConfig slot = (snapshot != null)
                ? snapshot.config().sounds().get(slotName)
                : SoundSlotConfig.SILENT;
        playSoundSlot(player, slot, slotName, snapshot);
    }

    /**
     * Plays the specified sound slot to the player per SB-041, SB-092, and T-138.
     * Immediate layers (delay <= 0) play synchronously; delayed layers are scheduled.
     */
    public void playSoundSlot(Player player, SoundSlotConfig slot, String slotName, RuntimeSnapshot snapshot) {
        playSoundSlot(player, slot, slotName, snapshot, soundPlayer);
    }

    private void playSoundSlot(Player player, SoundSlotConfig slot, String slotName, RuntimeSnapshot snapshot, SoundPlayer playback) {
        if (player == null || slot == null || slot.isSilent()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        for (SoundLayerConfig layer : slot.layers()) {
            if (layer.isSilent()) {
                continue;
            }
            if (layer.delay() <= 0L) {
                playLayerSafely(player, layer, slotName, snapshot, playback);
            } else {
                scheduleLayer(player, playerId, layer, slotName, snapshot, playback);
            }
        }
    }

    private void scheduleLayer(Player player, UUID playerId, SoundLayerConfig layer, String slotName, RuntimeSnapshot snapshot, SoundPlayer playback) {
        if (player == null) {
            return;
        }
        scheduleTracked(playerId, () -> {
            if (player.isOnline()) {
                playLayerSafely(player, layer, slotName, snapshot, playback);
            }
        }, layer.delay());
    }

    private void stopLayers(Player player, SoundSlotConfig slot) {
        for (SoundLayerConfig layer : slot.layers()) {
            if (!layer.isSilent()) {
                try {
                    player.stopSound(layer.key(), layer.category());
                } catch (Throwable ignored) {
                    // Match sound playback's quiet failure policy.
                }
            }
        }
    }

    public boolean hasPending(UUID playerId) {
        List<SoundScheduler.TaskHandle> handles = pendingTasks.get(playerId);
        return handles != null && !handles.isEmpty();
    }

    public void reserveEpisode(UUID playerId, long ticks) {
        scheduleTracked(playerId, () -> {}, ticks);
    }

    private boolean scheduleTracked(UUID playerId, Runnable action, long delayTicks) {
        AtomicReference<SoundScheduler.TaskHandle> handleRef = new AtomicReference<>();
        SoundScheduler.TaskHandle handle = scheduler.schedule(() -> {
            try {
                action.run();
            } finally {
                removePendingTask(playerId, handleRef.get());
            }
        }, delayTicks);
        if (handle != null) {
            handleRef.set(handle);
            addPendingTask(playerId, handle);
        }
        return handle != null;
    }

    private void playLayerSafely(Player player, SoundLayerConfig layer, String slotName, RuntimeSnapshot snapshot, SoundPlayer playback) {
        if (player == null || layer.isSilent()) {
            return;
        }
        try {
            playback.play(player, layer);
        } catch (Throwable t) {
            if (snapshot != null) {
                snapshot.config().sounds().logKeyWarning(slotName, layer.key(), t.getMessage());
            }
        }
    }

    /**
     * Cancels all pending delayed sound layers for the given player UUID.
     * Called when a player quits to guarantee scheduled layers do not fire.
     */
    public void cancelPending(UUID playerId) {
        if (playerId == null) {
            return;
        }
        silverfishService.registry().cleanForPlayer(playerId);
        Runnable stop = activeSoundStops.remove(playerId);
        if (stop != null) stop.run();
        List<SoundScheduler.TaskHandle> handles = pendingTasks.remove(playerId);
        if (handles != null) {
            for (SoundScheduler.TaskHandle handle : handles) {
                if (handle != null) {
                    try {
                        handle.cancel();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    public void cancelAllPending() {
        silverfishService.registry().cleanAll();
        for (UUID id : Set.copyOf(pendingTasks.keySet())) cancelPending(id);
        for (UUID id : Set.copyOf(activeSoundStops.keySet())) cancelPending(id);
    }

    private void addPendingTask(UUID playerId, SoundScheduler.TaskHandle handle) {
        pendingTasks.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>()).add(handle);
    }

    private void removePendingTask(UUID playerId, SoundScheduler.TaskHandle handle) {
        List<SoundScheduler.TaskHandle> handles = pendingTasks.get(playerId);
        if (handles != null && handle != null) {
            handles.remove(handle);
            if (handles.isEmpty()) {
                pendingTasks.remove(playerId, handles);
            }
        }
    }

    private boolean dispatchFakeAnnouncement(Player player, RuntimeSnapshot snapshot) {
        boolean isJoin = random.nextBoolean();
        String key = isJoin ? "effects.fake-connection.join" : "effects.fake-connection.leave";
        if (!snapshot.messages().isKnownKey(key)) key = isJoin ? "effects.fake-join" : "effects.fake-leave";
        if (!validLine(snapshot, key, Map.of("player", player.getName()), 160)) return false;
        Component announcement = messageRegistry.render(snapshot, key, Map.of("player", player.getName()));

        // Send privately to the affected player alone - never broadcasted or logged
        player.sendMessage(announcement);
        return true;
    }

    public FakeSilverfishService silverfishService() {
        return silverfishService;
    }
}
