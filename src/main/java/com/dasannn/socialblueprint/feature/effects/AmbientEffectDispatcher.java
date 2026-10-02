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
    private final Map<UUID, Map<UUID, Runnable>> sereneViewers = new java.util.HashMap<>();
    private final Map<UUID, Map<UUID, ActiveEntityEntry>> sereneAnimals = new java.util.HashMap<>();
    private final Map<UUID, ActivePresentationEntry> phantoms = new java.util.HashMap<>();
    private final Map<UUID, java.util.function.BooleanSupplier> directionGuards = new java.util.HashMap<>();
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
            case SILVERFISH -> dispatchSilverfish(player, config.presentation().phantom());
            case WHISPER -> dispatchWhisper(player, snapshot);
            case CREEPER_SOUND -> {
                dispatchCreeperSound(player, config, snapshot);
                yield true;
            }
            case FAKE_ANNOUNCEMENT -> dispatchFakeAnnouncement(player, snapshot);
        };
    }

    public boolean dispatchSerene(Player subject, String effect, RuntimeSnapshot snapshot,
                                  java.util.function.BooleanSupplier stillSerene) {
        var config = snapshot.config().effects().serenity();
        UUID owner = subject.getUniqueId();
        cancelPending(owner);
        directionGuards.put(owner, stillSerene);
        boolean dawn = effect.equals("dawn");
        Set<UUID> ids = sereneAudience(subject, dawn, config.observerRange());
        List<Player> audience = subject.getWorld().getPlayers().stream()
                .filter(p -> ids.contains(p.getUniqueId())).toList();
        Map<UUID, Runnable> viewers = new java.util.HashMap<>();
        for (Player viewer : audience) viewers.put(viewer.getUniqueId(), () -> {});
        long ticks = SereneEpisode.durationTicks(effect, config, snapshot.config().sounds());
        try {
            if (!startSereneAudience(owner, viewers, ticks)
                    || !watchSerene(subject, dawn, config.observerRange(), stillSerene, viewers, ticks)) {
                cancelPending(owner);
                return false;
            }
            boolean delivered = switch (effect) {
                case "dawn" -> dispatchSky(subject, config.dawnDuration(), config.dawnTime(), true, false);
                case "particles" -> dispatchParticles(subject, config.particles(), audience);
                case "source-less-sounds" -> {
                    var settings = config.sounds();
                    SoundSlotConfig slot = snapshot.config().sounds().get(settings.slot());
                    if (slot.isSilent()) yield false;
                    for (Player viewer : audience) {
                        viewers.put(viewer.getUniqueId(), () -> stopLayers(viewer, slot));
                        playSoundSlot(viewer, owner, slot, settings.slot(), snapshot, (recipient, layer) -> {
                            if (!stillSerene.getAsBoolean()) { cancelPending(owner); return; }
                            pruneSereneAudience(subject, dawn, config.observerRange(), viewers);
                            if (!viewers.containsKey(recipient.getUniqueId())) return;
                            Location origin = subject.getLocation();
                            double yaw = Math.toRadians(origin.getYaw());
                            Location at = origin.clone().add(-Math.sin(yaw) * settings.forward() - Math.cos(yaw) * settings.right(),
                                    settings.up(), Math.cos(yaw) * settings.forward() - Math.sin(yaw) * settings.right());
                            recipient.playSound(at, layer.key(), layer.category(), layer.volume(), layer.pitch());
                        });
                    }
                    yield true;
                }
                case "apparition" -> {
                    double yaw = Math.toRadians(subject.getLocation().getYaw());
                    Location at = subject.getLocation().clone().add(-Math.sin(yaw) * config.animalRange(),
                            0, Math.cos(yaw) * config.animalRange());
                    at.setYaw(subject.getLocation().getYaw() + 180F);
                    boolean shown = false;
                    PrivateGhost subjectAnimal = PrivateGhost.animal(subject, at, config.animal(),
                            silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft(config.animal())));
                    if (!safeAnimalViewer(subject, subjectAnimal.bounds())) yield false;
                    for (Player viewer : audience) {
                        PrivateGhost animal = viewer.getUniqueId().equals(owner) ? subjectAnimal : PrivateGhost.animal(viewer, at, config.animal(),
                                silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft(config.animal())));
                        if (!safeAnimalViewer(viewer, animal.bounds())) { viewers.remove(viewer.getUniqueId()); continue; }
                        silverfishService.registry().register(animal.entry());
                        UUID viewerId = viewer.getUniqueId();
                        sereneAnimals.computeIfAbsent(owner, ignored -> new java.util.HashMap<>()).put(viewerId, animal.entry());
                        viewers.put(viewerId, () -> {
                            silverfishService.registry().cleanDespawn(animal.entry());
                            Map<UUID, ActiveEntityEntry> animals = sereneAnimals.get(owner);
                            if (animals != null) {
                                animals.remove(viewerId);
                                if (animals.isEmpty()) sereneAnimals.remove(owner);
                            }
                        });
                        animal.show();
                        if (!watchAnimal(owner, viewer, animal, viewers, ticks)) {
                            cancelPending(owner);
                            yield false;
                        }
                        shown = true;
                    }
                    yield shown;
                }
                default -> false;
            };
            if (!delivered) cancelPending(owner);
            return delivered;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            cancelPending(owner);
            if (warnedKeys.add("serenity-" + effect)) logger.warning("Serenity " + effect + " unavailable: " + failure.getMessage());
            return false;
        }
    }

    private Set<UUID> sereneAudience(Player subject, boolean dawn, double range) {
        List<SereneEpisode.Candidate> candidates = new java.util.ArrayList<>();
        for (Player viewer : subject.getWorld().getPlayers()) {
            boolean sameWorld = viewer.getWorld().equals(subject.getWorld());
            candidates.add(new SereneEpisode.Candidate(viewer.getUniqueId(), viewer.isOnline(), sameWorld,
                    viewer.canSee(subject), subject.hasMetadata("vanished"),
                    sameWorld ? viewer.getLocation().distanceSquared(subject.getLocation()) : Double.POSITIVE_INFINITY));
        }
        return SereneEpisode.audience(subject.getUniqueId(), dawn, candidates, range);
    }

    private boolean safeAnimalViewer(Player viewer, SereneEpisode.Bounds bounds) {
        var reach = viewer.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (reach == null) return false;
        Location eye = viewer.getEyeLocation();
        var body = viewer.getBoundingBox();
        // Packet-only mobs remain pickable in vanilla. Never place one in interaction reach.
        // Include a movement margin; movement/teleport handlers also remove the owned visual immediately.
        return bounds.separatedFrom(new SereneEpisode.Bounds(body.getMinX(), body.getMinY(), body.getMinZ(),
                body.getMaxX(), body.getMaxY(), body.getMaxZ()))
                && bounds.outsideReach(eye.getX(), eye.getY(), eye.getZ(), reach.getValue() + 1);
    }

    private boolean watchAnimal(UUID owner, Player viewer, PrivateGhost animal, Map<UUID, Runnable> viewers, long remaining) {
        return scheduleTracked(owner, () -> {
            if (sereneViewers.get(owner) != viewers || !viewers.containsKey(viewer.getUniqueId())) return;
            if (!safeAnimalViewer(viewer, animal.bounds())) {
                Runnable cleanup = viewers.remove(viewer.getUniqueId());
                if (cleanup != null) cleanup.run();
            } else if (remaining > 1 && !watchAnimal(owner, viewer, animal, viewers, remaining - 1)) cancelPending(owner);
        }, 1);
    }

    private void pruneSereneAudience(Player subject, boolean dawn, double range, Map<UUID, Runnable> viewers) {
        for (UUID id : SereneEpisode.departed(Set.copyOf(viewers.keySet()), sereneAudience(subject, dawn, range))) {
            Runnable cleanup = viewers.remove(id);
            if (cleanup != null) cleanup.run();
        }
    }

    private boolean watchSerene(Player subject, boolean dawn, double range,
                                java.util.function.BooleanSupplier stillSerene, Map<UUID, Runnable> viewers, long remaining) {
        return scheduleTracked(subject.getUniqueId(), () -> {
            if (sereneViewers.get(subject.getUniqueId()) != viewers) return;
            if (!subject.isOnline() || !stillSerene.getAsBoolean()) {
                cancelPending(subject.getUniqueId());
                return;
            }
            pruneSereneAudience(subject, dawn, range, viewers);
            if (remaining > 1 && !watchSerene(subject, dawn, range, stillSerene, viewers, remaining - 1))
                cancelPending(subject.getUniqueId());
        }, 1);
    }

    private void endSereneAudience(UUID owner, Map<UUID, Runnable> viewers) {
        if (sereneViewers.remove(owner, viewers)) {
            viewers.values().forEach(Runnable::run);
            viewers.clear();
        }
    }

    boolean startSereneAudience(UUID owner, Map<UUID, Runnable> viewers, long ticks) {
        sereneViewers.put(owner, viewers);
        if (scheduleTracked(owner, () -> endSereneAudience(owner, viewers), ticks)) return true;
        endSereneAudience(owner, viewers);
        return false;
    }

    void removeSereneViewer(UUID id) {
        for (Map<UUID, Runnable> viewers : sereneViewers.values()) {
            Runnable cleanup = viewers.remove(id);
            if (cleanup != null) cleanup.run();
        }
    }

    void removeAnimalViewer(UUID id) {
        ActivePresentationEntry phantom = phantoms.remove(id);
        if (phantom != null) silverfishService.registry().cleanPresentation(phantom);
        for (UUID owner : Set.copyOf(sereneAnimals.keySet())) {
            Map<UUID, ActiveEntityEntry> animals = sereneAnimals.get(owner);
            if (animals != null && animals.containsKey(id)) {
                Map<UUID, Runnable> viewers = sereneViewers.get(owner);
                Runnable cleanup = viewers == null ? null : viewers.remove(id);
                if (cleanup != null) cleanup.run();
            }
        }
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
        Location at = player.getLocation().clone().add(-Math.sin(yaw) * config.range(), 0, Math.cos(yaw) * config.range());
        at.setYaw(player.getLocation().getYaw() + 180F);
        PrivateGhost body = null;
        try {
            Object type = silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft("mannequin"));
            if (type != null) body = PrivateGhost.animal(player, at, "mannequin", type);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (warnedKeys.add("mannequin-bridge")) logger.warning("Private mannequin unavailable; using ghost label: " + failure.getMessage());
        }
        if (body != null && !safeAnimalViewer(player, body.bounds())) return false;
        PrivateGhost ghost;
        try {
            ghost = new PrivateGhost(player, at.clone().add(0, 2.2, 0), label,
                    silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft("text_display")));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (warnedKeys.add("ghost-bridge")) java.util.logging.Logger.getLogger(getClass().getName())
                    .warning("Private ghost bridge unavailable; skipping victim ghosts: " + failure.getMessage());
            return false;
        }
        cancelPending(player.getUniqueId());
        PrivateGhost mannequin = body;
        List<ActiveEntityEntry> entities = mannequin == null ? List.of(ghost.entry()) : List.of(ghost.entry(), mannequin.entry());
        if (!showEntities(entities, AmbientEffectType.VICTIM_GHOST, config.durationTicks(), () -> {
            try {
                if (mannequin != null) mannequin.show();
                ghost.show();
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        })) return false;
        ActivePresentationEntry entry = phantoms.get(player.getUniqueId());
        try {
            if (mannequin != null && !watchPhantom(player, mannequin, entry, config.durationTicks())) {
                removeAnimalViewer(player.getUniqueId());
                return false;
            }
            return true;
        }
        catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    boolean showEntities(List<ActiveEntityEntry> entities, AmbientEffectType type, long ticks, Runnable sendSpawn) {
        UUID owner = entities.getFirst().targetPlayerId();
        AmbientEntityRegistry registry = silverfishService.registry();
        entities.forEach(registry::register);
        try {
            ActivePresentationEntry entry = startPresentation(owner, type, ticks, () -> {
                entities.forEach(registry::cleanDespawn);
                phantoms.remove(owner);
            });
            if (entry == null) return false;
            phantoms.put(owner, entry);
            sendSpawn.run();
            return true;
        } catch (RuntimeException failure) {
            cancelPending(owner);
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
        boolean night = config.mode().equals("night");
        return dispatchSky(player, config.durationTicks(), 18000L, night, !night);
    }

    private boolean dispatchSky(Player player, int duration, long time, boolean changesTime, boolean changesWeather) {
        org.bukkit.WeatherType previousWeather = player.getPlayerWeather();
        UUID worldId = player.getWorld().getUID();
        SkyPresentation sky = new SkyPresentation(player.getPlayerTimeOffset(), player.isPlayerTimeRelative(),
                previousWeather == null ? null : previousWeather.name(), time, false, "DOWNFALL", changesTime, changesWeather);
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.SKY, duration, () -> {
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
            if (changesTime) player.setPlayerTime(time, false);
            if (changesWeather) player.setPlayerWeather(org.bukkit.WeatherType.DOWNFALL);
            return true;
        } catch (RuntimeException failure) {
            silverfishService.registry().cleanPresentation(entry);
            return false;
        }
    }

    private boolean dispatchParticles(Player player, PresentationConfig.Particles config) {
        return dispatchParticles(player, config, List.of(player));
    }

    boolean dispatchParticles(Player player, PresentationConfig.Particles config, List<Player> audience) {
        if (!config.fitsAudience(audience.size())) return false;
        org.bukkit.Particle particle = org.bukkit.Particle.valueOf(config.type().toUpperCase(java.util.Locale.ROOT));
        ActivePresentationEntry entry = startPresentation(player.getUniqueId(), AmbientEffectType.PARTICLES, config.totalTicks(), () -> {});
        if (entry == null) return false;
        try {
            Location origin = player.getLocation();
            for (int i = 0; i < config.count(); i++) {
                ParticlePoint point = ParticlePoint.at(config, i);
                Runnable emit = () -> {
                    for (Player viewer : audience)
                        if (viewer.isOnline() && viewer.getWorld().equals(origin.getWorld())
                                && (!sereneViewers.containsKey(player.getUniqueId())
                                || sereneViewers.get(player.getUniqueId()).containsKey(viewer.getUniqueId()))) viewer.spawnParticle(particle, origin.clone().add(point.x(), point.y(), point.z()), 1, 0, 0, 0, 0);
                };
                long delay = config.emissionDelay(i);
                if (delay == 0) emit.run();
                else if (!scheduleTracked(player.getUniqueId(), emit, delay)) {
                    cancelPending(player.getUniqueId());
                    return false;
                }
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

    boolean dispatchSilverfish(Player player, PresentationConfig.Phantom config) {
        if (player.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
            warnPeacefulPhantoms();
            return false;
        }
        String mob = config.mobs().get(random.nextInt(config.mobs().size()));
        double yaw = Math.toRadians(player.getLocation().getYaw());
        Location at = player.getLocation().clone().add(-Math.sin(yaw) * config.distance(), 0,
                Math.cos(yaw) * config.distance());
        at.setYaw(player.getLocation().getYaw() + 180F);
        UUID owner = player.getUniqueId();
        try {
            PrivateGhost phantom = PrivateGhost.animal(player, at, mob,
                    silverfishService.resolveEntityType(org.bukkit.NamespacedKey.fromString(mob)));
            if (!safeAnimalViewer(player, phantom.bounds())) return false;
            if (!showPhantom(phantom.entry(), config.durationTicks(), () -> {
                try { phantom.show(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            })) return false;
            ActivePresentationEntry entry = phantoms.get(owner);
            if (!watchPhantom(player, phantom, entry, config.durationTicks())) {
                removeAnimalViewer(owner);
                return false;
            }
            return true;
        } catch (PrivateGhost.MobUnavailableException skipped) {
            warnPeacefulPhantoms();
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            cancelPending(owner);
            if (warnedKeys.add("phantom-bridge")) logger.warning("Private phantom bridge unavailable: " + failure.getMessage());
            return false;
        }
    }

    private void warnPeacefulPhantoms() {
        if (warnedKeys.add("phantom-peaceful")) logger.info("Private phantom mobs need a non-peaceful difficulty; skipping this episode.");
    }

    // Plain lifecycle seam: register before spawn, retain until expiry, and remove on interrupted delivery.
    boolean showPhantom(ActiveEntityEntry phantom, long ticks, Runnable sendSpawn) {
        return showEntities(List.of(phantom), AmbientEffectType.SILVERFISH, ticks, sendSpawn);
    }

    private boolean watchPhantom(Player viewer, PrivateGhost phantom, ActivePresentationEntry entry, long remaining) {
        return scheduleTracked(viewer.getUniqueId(), () -> {
            if (phantoms.get(viewer.getUniqueId()) != entry) return;
            if (!viewer.isOnline() || !safeAnimalViewer(viewer, phantom.bounds())) removeAnimalViewer(viewer.getUniqueId());
            else if (remaining > 1 && !watchPhantom(viewer, phantom, entry, remaining - 1)) removeAnimalViewer(viewer.getUniqueId());
        }, 1);
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
        if (player == null) return;
        playSoundSlot(player, player.getUniqueId(), slot, slotName, snapshot, playback);
    }

    private void playSoundSlot(Player player, UUID owner, SoundSlotConfig slot, String slotName, RuntimeSnapshot snapshot, SoundPlayer playback) {
        if (player == null || slot == null || slot.isSilent()) {
            return;
        }
        for (SoundLayerConfig layer : slot.layers()) {
            if (layer.isSilent()) {
                continue;
            }
            if (layer.delay() <= 0L) {
                playLayerSafely(player, layer, slotName, snapshot, playback);
            } else {
                scheduleLayer(player, owner, layer, slotName, snapshot, playback);
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

    // Main-thread guard, including delayed layers after a direction changes between scheduler checks.
    boolean guardDirection(UUID owner, java.util.function.BooleanSupplier eligible, long remaining) {
        directionGuards.put(owner, eligible);
        return scheduleTracked(owner, () -> {
            if (!eligible.getAsBoolean()) cancelPending(owner);
            else if (remaining > 1 && hasPending(owner) && !guardDirection(owner, eligible, remaining - 1)) cancelPending(owner);
        }, 1);
    }

    private boolean scheduleTracked(UUID playerId, Runnable action, long delayTicks) {
        AtomicReference<SoundScheduler.TaskHandle> handleRef = new AtomicReference<>();
        SoundScheduler.TaskHandle handle = scheduler.schedule(() -> {
            try {
                var guard = directionGuards.get(playerId);
                if (guard != null && !guard.getAsBoolean()) cancelPending(playerId);
                else action.run();
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
        directionGuards.remove(playerId);
        removeSereneViewer(playerId);
        Map<UUID, Runnable> viewers = sereneViewers.remove(playerId);
        if (viewers != null) viewers.values().forEach(Runnable::run);
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
                directionGuards.remove(playerId);
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
