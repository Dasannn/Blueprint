package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SoundLayerConfig;
import com.dasannn.socialblueprint.config.SoundSlotConfig;
import net.kyori.adventure.text.Component;
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
 * Dispatches individual low-status ambient effects privately to an affected player
 * per SB-040, SB-041, SB-092, Decision 0002, and T-138.
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

    private final Plugin plugin;
    private final FakeSilverfishService silverfishService;
    private final SoundScheduler scheduler;
    private final SoundPlayer soundPlayer;
    private final Map<UUID, List<SoundScheduler.TaskHandle>> pendingTasks = new ConcurrentHashMap<>();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
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
        this.plugin = plugin;
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
            return () -> {};
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

        return switch (type) {
            case SILVERFISH -> dispatchSilverfish(player, config);
            case WHISPER -> {
                dispatchWhisper(player, snapshot);
                yield true;
            }
            case CREEPER_SOUND -> {
                dispatchCreeperSound(player, snapshot);
                yield true;
            }
            case FAKE_ANNOUNCEMENT -> {
                dispatchFakeAnnouncement(player, config, snapshot);
                yield true;
            }
        };
    }

    private boolean dispatchSilverfish(Player player, EffectsConfigSection config) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 1.5 + random.nextDouble() * 2.0;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;

        Location at = player.getLocation().clone().add(dx, 0, dz);
        ActiveEntityEntry entry = silverfishService.spawnSilverfish(player, at, config.silverfish().durationTicks());
        return entry != null;
    }

    private void dispatchWhisper(Player player, RuntimeSnapshot snapshot) {
        int idx = random.nextInt(3) + 1;
        String key = "effects.whisper-" + idx;
        String raw = (snapshot != null && snapshot.messages() != null)
                ? snapshot.messages().resolveRaw(key, warnedKeys, null)
                : "";
        player.sendMessage(ColorParser.parse(raw));
    }

    private void dispatchCreeperSound(Player player, RuntimeSnapshot snapshot) {
        playSoundSlot(player, "creeper-fuse", snapshot);
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
        if (player == null || slot == null || slot.isSilent()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        for (SoundLayerConfig layer : slot.layers()) {
            if (layer.isSilent()) {
                continue;
            }
            if (layer.delay() <= 0L) {
                playLayerSafely(player, layer, slotName, snapshot);
            } else {
                scheduleLayer(player, playerId, layer, slotName, snapshot);
            }
        }
    }

    private void scheduleLayer(Player player, UUID playerId, SoundLayerConfig layer, String slotName, RuntimeSnapshot snapshot) {
        if (player == null) {
            return;
        }
        AtomicReference<SoundScheduler.TaskHandle> handleRef = new AtomicReference<>();
        SoundScheduler.TaskHandle handle = scheduler.schedule(() -> {
            try {
                if (player.isOnline()) {
                    playLayerSafely(player, layer, slotName, snapshot);
                }
            } finally {
                removePendingTask(playerId, handleRef.get());
            }
        }, layer.delay());

        if (handle != null) {
            handleRef.set(handle);
            addPendingTask(playerId, handle);
        }
    }

    private void playLayerSafely(Player player, SoundLayerConfig layer, String slotName, RuntimeSnapshot snapshot) {
        if (player == null || layer.isSilent()) {
            return;
        }
        try {
            soundPlayer.play(player, layer);
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

    private void dispatchFakeAnnouncement(Player player, EffectsConfigSection config, RuntimeSnapshot snapshot) {
        List<String> names = config.fakeAnnouncement().fakeNames();
        String fakeName = (!names.isEmpty()) ? names.get(random.nextInt(names.size())) : "Herobrine";

        boolean isJoin = random.nextBoolean();
        String key = isJoin ? "effects.fake-join" : "effects.fake-leave";
        String raw = (snapshot != null && snapshot.messages() != null)
                ? snapshot.messages().resolveRaw(key, warnedKeys, null)
                : "";
        Component announcement = ColorParser.renderTemplate(raw, Map.of("player", fakeName));

        // Send privately to the affected player alone - never broadcasted or logged
        player.sendMessage(announcement);
    }

    public FakeSilverfishService silverfishService() {
        return silverfishService;
    }
}
