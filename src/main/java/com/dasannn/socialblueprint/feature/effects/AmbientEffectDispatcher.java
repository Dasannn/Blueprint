package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SoundLayerConfig;
import com.dasannn.socialblueprint.config.SoundSlotConfig;
import com.dasannn.socialblueprint.config.PresentationConfig;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
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
    private final Map<UUID, ApparitionFollow> apparitionFollows = new java.util.HashMap<>();
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
        return dispatch(player, type, config, snapshot, PsychosisLevel.HIGH);
    }

    public boolean dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot,
                            PsychosisLevel level) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (player.getWorld() != null && !snapshot.config().worldRules().allowsWorld(player.getWorld().getName())) return false;
        return attempt(player.getUniqueId(), () -> switch (type) {
            case ADVANCEMENT_TOAST -> {
                List<String> keys = snapshot.messages().lineKeys("effects.advancement-toast.lines");
                yield !keys.isEmpty() && ToastDecision.describe(keys.getFirst(), config.presentation().toast()).deliveryAvailable();
            }
            case BOSS_BAR -> dispatchBar(player, config.presentation().bar(), snapshot);
            case FALSE_DEATH -> dispatchFalseDeath(player, config.presentation().deathRange(), snapshot);
            case SKY -> dispatchSky(player, config.presentation().sky(), level);
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
        });
    }

    /** A skipped renderer rolls back only its own tasks and presentations. */
    private boolean attempt(UUID owner, java.util.function.BooleanSupplier delivery) {
        Set<SoundScheduler.TaskHandle> beforeTasks = Set.copyOf(pendingTasks.getOrDefault(owner, List.of()));
        Set<ActivePresentationEntry> beforePresentations = silverfishService.registry().presentationsFor(owner);
        boolean success = false;
        try { success = delivery.getAsBoolean(); return success; }
        catch (RuntimeException skipped) { return false; }
        finally {
            if (!success) {
                for (SoundScheduler.TaskHandle handle : List.copyOf(pendingTasks.getOrDefault(owner, List.of())))
                    if (!beforeTasks.contains(handle)) { handle.cancel(); removePendingTask(owner, handle); }
                for (ActivePresentationEntry entry : silverfishService.registry().presentationsFor(owner))
                    if (!beforePresentations.contains(entry)) silverfishService.registry().cleanPresentation(entry);
            }
        }
    }

    public boolean dispatchSerene(Player subject, String effect, RuntimeSnapshot snapshot,
                                  java.util.function.BooleanSupplier stillSerene) {
        if (subject.getWorld() != null && !snapshot.config().worldRules().allowsWorld(subject.getWorld().getName())) return false;
        var config = snapshot.config().effects().serenity();
        String animalKind = config.animals().get(random.nextInt(config.animals().size()));
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
                    Location at = apparitionGround(subject, config.animalRange());
                    if (at == null) yield false;
                    Map<UUID, PrivateGhost> movingAnimals = new java.util.HashMap<>();
                    boolean shown = false;
                    PrivateGhost subjectAnimal = PrivateGhost.animal(subject, at, animalKind,
                            silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft(animalKind)));
                    if (!safeAnimalViewer(subject, subjectAnimal.bounds()) || !clearAnimalSpace(at, subjectAnimal.bounds())
                            || !visibleAnimal(subject, subjectAnimal.bounds())) yield false;
                    for (Player viewer : audience) {
                        PrivateGhost animal = viewer.getUniqueId().equals(owner) ? subjectAnimal : PrivateGhost.animal(viewer, at, animalKind,
                                silverfishService.resolveEntityType(org.bukkit.NamespacedKey.minecraft(animalKind)));
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
                        movingAnimals.put(viewerId, animal);
                        shown = true;
                    }
                    if (shown) {
                        var follow = new ApparitionFollow(subject, at, movingAnimals, viewers, config);
                        apparitionFollows.put(owner, follow);
                        if (!follow.schedule(ticks)) { cancelPending(owner); yield false; }
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

    private Location apparitionGround(Player subject, double range) {
        Location origin = subject.getLocation();
        var offset = SereneEpisode.apparitionOffset(origin.getYaw(), range, random.nextDouble());
        return apparitionGround(origin, origin.getX() + offset.x(), origin.getZ() + offset.z());
    }

    private Location apparitionGround(Location origin, double x, double z) {
        Location probe = new Location(origin.getWorld(), x, origin.getY() + 2, z);
        if (!Double.isFinite(probe.getX()) || !Double.isFinite(probe.getZ())
                || !probe.getWorld().isChunkLoaded(probe.getBlockX() >> 4, probe.getBlockZ() >> 4)) return null;
        // ponytail: search only two blocks up/four down; skip cliffs, expand only if live terrain needs it.
        var hit = probe.getWorld().rayTraceBlocks(probe, new org.bukkit.util.Vector(0, -1, 0), 6,
                org.bukkit.FluidCollisionMode.ALWAYS, false);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlock().isLiquid()) return null;
        var point = hit.getHitPosition();
        Location at = new Location(origin.getWorld(), point.getX(), point.getY() + .01, point.getZ());
        at.setYaw((float) Math.toDegrees(Math.atan2(at.getX() - origin.getX(), origin.getZ() - at.getZ())));
        return at;
    }

    private boolean clearAnimalSpace(Location at, SereneEpisode.Bounds bounds) {
        var world = at.getWorld();
        if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()) return false;
        // Conservative enclosing cubes also exclude fluids and complicated partial-block shapes.
        for (int x = (int) Math.floor(bounds.minX()); x <= (int) Math.floor(bounds.maxX()); x++)
            for (int z = (int) Math.floor(bounds.minZ()); z <= (int) Math.floor(bounds.maxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                for (int y = (int) Math.floor(bounds.minY()); y <= (int) Math.floor(bounds.maxY()); y++) {
                    var block = world.getBlockAt(x, y, z);
                    if (!block.isPassable() || block.isLiquid()) return false;
                }
            }
        return true;
    }

    private boolean visibleAnimal(Player subject, SereneEpisode.Bounds bounds) {
        Location eye = subject.getEyeLocation();
        var toward = new org.bukkit.util.Vector((bounds.minX() + bounds.maxX()) / 2 - eye.getX(),
                (bounds.minY() + bounds.maxY()) / 2 - eye.getY(), (bounds.minZ() + bounds.maxZ()) / 2 - eye.getZ());
        return SereneEpisode.inView(eye.getYaw(), eye.getPitch(), toward.getX(), toward.getY(), toward.getZ())
                && eye.getWorld().rayTraceBlocks(eye, toward, toward.length()) == null;
    }

    private boolean safeAnimalViewer(Player viewer, SereneEpisode.Bounds bounds) {
        return safeAnimalViewerAt(viewer, bounds, viewer.getLocation());
    }

    private static SereneEpisode.Position position(Location at) {
        return new SereneEpisode.Position(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), at.getYaw());
    }

    private final class ApparitionFollow {
        final Player subject;
        final Map<UUID, PrivateGhost> animals;
        final Map<UUID, Runnable> viewers;
        final com.dasannn.socialblueprint.config.SerenityEffectsConfig config;
        Location last, previousSubject;

        ApparitionFollow(Player subject, Location at, Map<UUID, PrivateGhost> animals, Map<UUID, Runnable> viewers,
                         com.dasannn.socialblueprint.config.SerenityEffectsConfig config) {
            this.subject = subject; this.last = at; this.previousSubject = subject.getLocation();
            this.animals = animals; this.viewers = viewers; this.config = config;
        }

        boolean schedule(long remaining) {
            int delay = config.followUpdateTicks();
            if (remaining <= delay) return true;
            return scheduleTracked(subject.getUniqueId(), () -> {
                if (apparitionFollows.get(subject.getUniqueId()) != this) return;
                moveTo(subject.getLocation());
                if (apparitionFollows.get(subject.getUniqueId()) == this && !schedule(remaining - delay))
                    cancelPending(subject.getUniqueId());
            }, delay);
        }

        void moveTo(Location origin) {
            UUID owner = subject.getUniqueId();
            var direction = directionGuards.get(owner);
            if (!subject.isOnline() || (direction != null && !direction.getAsBoolean())) { cancelPending(owner); return; }
            if (SereneEpisode.followEnds(position(previousSubject), position(origin), false)) { cancelPending(owner); return; }
            var reach = subject.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
            if (reach == null || !Double.isFinite(reach.getValue())) { cancelPending(owner); return; }
            // Enclose the widest configured animal plus the existing reach/movement margin.
            double guard = reach.getValue() + SereneEpisode.REACH_MARGIN + .75;
            double distance = Math.max(config.followDistance(), guard + .25);
            var candidate = SereneEpisode.followCandidate(position(origin), position(last), distance, guard, config.followUpdateTicks());
            Location ground = apparitionGround(origin, candidate.x(), candidate.z());
            PrivateGhost model = animals.values().stream().findFirst().orElse(null);
            if (ground != null && model != null && (!clearAnimalSpace(ground, model.boundsAt(ground))
                    || !clearAnimalPath(last, ground, model))) ground = null;
            var decision = SereneEpisode.follow(position(previousSubject), position(origin), position(last),
                    ground == null ? null : position(ground), guard, false);
            previousSubject = origin.clone();
            Location next = new Location(origin.getWorld(), decision.position().x(), decision.position().y(),
                    decision.position().z(), (float) decision.position().yaw(), 0);
            try {
                for (var entry : animals.entrySet()) {
                    if (!viewers.containsKey(entry.getKey())) continue;
                    Player viewer = subject.getServer().getPlayer(entry.getKey());
                    Location viewerAt = entry.getKey().equals(owner) ? origin : viewer == null ? null : viewer.getLocation();
                    var destinationBounds = entry.getValue().boundsAt(next);
                    if (!entry.getKey().equals(owner)) {
                        var oldBounds = entry.getValue().bounds();
                        destinationBounds = new SereneEpisode.Bounds(Math.min(oldBounds.minX(), destinationBounds.minX()),
                                Math.min(oldBounds.minY(), destinationBounds.minY()), Math.min(oldBounds.minZ(), destinationBounds.minZ()),
                                Math.max(oldBounds.maxX(), destinationBounds.maxX()), Math.max(oldBounds.maxY(), destinationBounds.maxY()),
                                Math.max(oldBounds.maxZ(), destinationBounds.maxZ()));
                    }
                    if (viewer == null || !safeAnimalViewerAt(viewer, destinationBounds, viewerAt)) {
                        if (entry.getKey().equals(owner)) { cancelPending(owner); return; }
                        Runnable cleanup = viewers.remove(entry.getKey());
                        if (cleanup != null) cleanup.run();
                    } else entry.getValue().move(next);
                }
                last = next;
            } catch (ReflectiveOperationException | RuntimeException failure) { cancelPending(owner); }
        }
    }

    private boolean clearAnimalPath(Location from, Location to, PrivateGhost model) {
        // Probe the swept body too: client interpolation must not walk through a wall.
        // ponytail: cap a sweep at 24 blocks; longer retreats end safely, expand only if live terrain requires it.
        if (from.distanceSquared(to) > 24 * 24) return false;
        int steps = Math.max(1, (int) Math.ceil(from.distance(to) * 4));
        for (int i = 1; i < steps; i++) {
            Location at = from.clone().add((to.getX() - from.getX()) * i / steps,
                    (to.getY() - from.getY()) * i / steps, (to.getZ() - from.getZ()) * i / steps);
            if (!clearAnimalSpace(at, model.boundsAt(at))) return false;
        }
        return true;
    }

    private boolean safeAnimalViewerAt(Player viewer, SereneEpisode.Bounds bounds, Location at) {
        var reach = viewer.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (reach == null || at == null || !at.getWorld().equals(viewer.getWorld())) return false;
        Location current = viewer.getLocation(), eye = viewer.getEyeLocation();
        double dx = at.getX() - current.getX(), dy = at.getY() - current.getY(), dz = at.getZ() - current.getZ();
        var body = viewer.getBoundingBox();
        return bounds.separatedFrom(new SereneEpisode.Bounds(body.getMinX() + dx, body.getMinY() + dy, body.getMinZ() + dz,
                body.getMaxX() + dx, body.getMaxY() + dy, body.getMaxZ() + dz))
                && bounds.outsideReach(eye.getX() + dx, eye.getY() + dy, eye.getZ() + dz, reach.getValue() + SereneEpisode.REACH_MARGIN);
    }

    void moveAnimalViewer(Player viewer, Location destination) {
        removePrivateAnimalViewer(viewer.getUniqueId());
        if (destination == null) return;
        var own = apparitionFollows.get(viewer.getUniqueId());
        if (own != null && own.animals.containsKey(viewer.getUniqueId()) && (SereneEpisode.followEnds(position(own.previousSubject), position(destination), false)
                || !safeAnimalViewerAt(viewer, own.animals.get(viewer.getUniqueId()).bounds(), destination))) own.moveTo(destination);
        for (var entry : java.util.List.copyOf(apparitionFollows.values())) {
            if (entry == own) continue;
            PrivateGhost animal = entry.animals.get(viewer.getUniqueId());
            if (animal != null && !safeAnimalViewerAt(viewer, animal.bounds(), destination)) {
                Runnable cleanup = entry.viewers.remove(viewer.getUniqueId());
                if (cleanup != null) cleanup.run();
            }
        }
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
            apparitionFollows.remove(owner);
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

    private void removePrivateAnimalViewer(UUID id) {
        for (ActivePresentationEntry entry : silverfishService.registry().presentationsFor(id))
            if (entry.type() == AmbientEffectType.SILVERFISH || entry.type() == AmbientEffectType.VICTIM_GHOST)
                silverfishService.registry().cleanPresentation(entry);
    }

    void removeAnimalViewer(UUID id) {
        removePrivateAnimalViewer(id);
        if (apparitionFollows.containsKey(id)) cancelPending(id);
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
        if (player.getWorld() != null && !snapshot.config().worldRules().allowsWorld(player.getWorld().getName())) return false;
        return attempt(player.getUniqueId(), () -> renderVictimGhost(player, config, snapshot, name));
    }

    private boolean renderVictimGhost(Player player, PresentationConfig.Ghost config, RuntimeSnapshot snapshot, String name) {
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
        PrivateGhost mannequin = body;
        List<ActiveEntityEntry> entities = mannequin == null ? List.of(ghost.entry()) : List.of(ghost.entry(), mannequin.entry());
        if (!showEntities(entities, AmbientEffectType.VICTIM_GHOST, config.durationTicks(), () -> {
            try {
                if (mannequin != null) mannequin.show();
                ghost.show();
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        })) return false;
        ActivePresentationEntry entry = phantomPresentation(player.getUniqueId(), AmbientEffectType.VICTIM_GHOST);
        try {
            if (mannequin != null && !watchPhantom(player, mannequin, entry, config.durationTicks())) {
                silverfishService.registry().cleanPresentation(entry);
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
        return attempt(entities.getFirst().targetPlayerId(), () -> renderEntities(entities, type, ticks, sendSpawn));
    }

    private boolean renderEntities(List<ActiveEntityEntry> entities, AmbientEffectType type, long ticks, Runnable sendSpawn) {
        UUID owner = entities.getFirst().targetPlayerId();
        AmbientEntityRegistry registry = silverfishService.registry();
        entities.forEach(registry::register);
        ActivePresentationEntry entry = null;
        try {
            entry = startPresentation(owner, type, ticks, () -> {
                entities.forEach(registry::cleanDespawn);
            });
            if (entry == null) return false;
            sendSpawn.run();
            return true;
        } catch (RuntimeException failure) {
            if (entry != null) registry.cleanPresentation(entry);
            entities.forEach(registry::cleanDespawn);
            return false;
        }
    }

    private ActivePresentationEntry phantomPresentation(UUID owner, AmbientEffectType type) {
        return silverfishService.registry().presentationsFor(owner).stream()
                .filter(entry -> entry.type() == type).findFirst().orElse(null);
    }

    public void restoreBlocks(UUID playerId) {
        AmbientEntityRegistry registry = silverfishService.registry();
        for (ActivePresentationEntry entry : registry.presentationsFor(playerId))
            if (entry.type() == AmbientEffectType.BLOCK_CHANGE || entry.type() == AmbientEffectType.SIGN)
                registry.cleanPresentation(entry);
    }

    private boolean dispatchSky(Player player, PresentationConfig.Sky config,
                                PsychosisLevel level) {
        SkyDecision sky = SkyDecision.choose(level, config.mode(), random);
        if (!sky.night() && !sky.storm()) return false;
        return dispatchSky(player, config.durationTicks(), 18000L, sky.night(), sky.storm());
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
        config = config.choose(random);
        org.bukkit.Particle particle = org.bukkit.Particle.valueOf(config.types().getFirst().toUpperCase(java.util.Locale.ROOT));
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
                    silverfishService.registry().cleanPresentation(entry);
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
            ActivePresentationEntry entry = phantomPresentation(owner, AmbientEffectType.SILVERFISH);
            if (!watchPhantom(player, phantom, entry, config.durationTicks())) {
                silverfishService.registry().cleanPresentation(entry);
                return false;
            }
            return true;
        } catch (PrivateGhost.MobUnavailableException skipped) {
            warnPeacefulPhantoms();
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ActivePresentationEntry entry = phantomPresentation(owner, AmbientEffectType.SILVERFISH);
            if (entry != null) silverfishService.registry().cleanPresentation(entry);
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
            if (!silverfishService.registry().presentationsFor(viewer.getUniqueId()).contains(entry)) return;
            if (!viewer.isOnline() || !safeAnimalViewer(viewer, phantom.bounds())) silverfishService.registry().cleanPresentation(entry);
            else if (remaining > 1 && !watchPhantom(viewer, phantom, entry, remaining - 1)) silverfishService.registry().cleanPresentation(entry);
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
        List<String> keys = snapshot.messages().lineKeys("effects.false-death.lines");
        if (keys.isEmpty()) return false;
        String key = keys.get(random.nextInt(keys.size()));
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
        apparitionFollows.remove(playerId);
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
