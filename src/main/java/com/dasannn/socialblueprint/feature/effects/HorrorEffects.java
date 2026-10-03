package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/** Thin main-thread private renderers; all restoration belongs to the existing registry. */
final class HorrorEffects {
    private final AmbientEffectDispatcher dispatcher;
    private final Random random = new Random();
    private final Map<UUID, Watcher> watchers = new HashMap<>();
    private final Map<UUID, Integer> wordIndexes = new HashMap<>();
    private record Watcher(Location target, SereneEpisode.Bounds bounds, double threshold, ActivePresentationEntry entry) {}

    HorrorEffects(AmbientEffectDispatcher dispatcher) { this.dispatcher = dispatcher; }

    boolean dispatch(Player player, AmbientEffectType type, HorrorConfig config, RuntimeSnapshot snapshot,
                     MessageRegistry messages) {
        return switch (type) {
            case FOOTSTEPS -> footsteps(player, config);
            case WATCHER -> watcher(player, config);
            case NEARBY_NOISES -> noises(player, config);
            case TORCH_FLICKER -> flicker(player, config);
            case SUBLIMINAL -> subliminal(player, config, snapshot, messages);
            case RED_VIGNETTE -> vignette(player, config);
            case FAKE_LIGHTNING -> lightning(player, config, snapshot);
            default -> false;
        };
    }
    private AmbientEntityRegistry registry() { return dispatcher.silverfishService().registry(); }
    private void end(ActivePresentationEntry entry) { registry().cleanPresentation(entry); }

    private boolean footsteps(Player player, HorrorConfig config) {
        if (config.stepVolume() == 0) return false;
        Location origin = player.getLocation();
        Block ground = origin.clone().subtract(0, .1, 0).getBlock();
        if (ground.isEmpty() || ground.isLiquid()) return false;
        Set<String> playedKeys = new HashSet<>();
        ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.FOOTSTEPS,
                config.footstepsTicks(), () -> playedKeys.forEach(key -> player.stopSound(key, SoundCategory.BLOCKS)));
        if (entry == null) return false;
        for (var step : HorrorDecision.footsteps(origin.getYaw(), config)) {
            Runnable play = () -> {
                if (entry.ended().get() || !player.isOnline() || !player.getWorld().equals(origin.getWorld())) return;
                Location current = player.getLocation();
                Block underfoot = current.clone().subtract(0, .1, 0).getBlock();
                if (underfoot.isEmpty() || underfoot.isLiquid()) return;
                String key = underfoot.getBlockData().getSoundGroup().getStepSound().getKey().toString();
                playedKeys.add(key);
                double angle = Math.toRadians(current.getYaw() - origin.getYaw());
                player.playSound(current.add(Math.cos(angle) * step.x() - Math.sin(angle) * step.z(), 0,
                                Math.sin(angle) * step.x() + Math.cos(angle) * step.z()),
                        key, SoundCategory.BLOCKS, config.stepVolume(), config.stepPitch());
            };
            if (step.tick() == 0) play.run();
            else if (!dispatcher.scheduleTracked(player.getUniqueId(), play, step.tick())) { end(entry); return false; }
        }
        return true;
    }

    /** Scan loaded blocks only, never load terrain to produce an episode. */
    private List<Block> nearby(Player player, double range, boolean lights) {
        Location origin = player.getLocation();
        List<Block> result = new ArrayList<>();
        int radius = (int) Math.ceil(range);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            int bx = origin.getBlockX() + x, bz = origin.getBlockZ() + z;
            if (!origin.getWorld().isChunkLoaded(bx >> 4, bz >> 4)) continue;
            // ponytail: seven vertical layers bound each scan; expand if live indoor use needs it.
            for (int y = -3; y <= 3; y++) {
                int by = origin.getBlockY() + y;
                if (by < origin.getWorld().getMinHeight() || by >= origin.getWorld().getMaxHeight()) continue;
                Block block = origin.getWorld().getBlockAt(bx, by, bz);
                if (block.getLocation().add(.5, .5, .5).distanceSquared(origin) > range * range) continue;
                String material = block.getType().name().toLowerCase(Locale.ROOT);
                if (lights ? HorrorDecision.light(material) : !HorrorDecision.noiseKey(material).isEmpty()) result.add(block);
            }
        }
        return result;
    }

    private boolean noises(Player player, HorrorConfig config) {
        if (config.noiseVolume() == 0) return false;
        List<Block> blocks = nearby(player, config.noiseRange(), false);
        if (blocks.isEmpty()) return false;
        Block block = blocks.get(random.nextInt(blocks.size()));
        String key = HorrorDecision.noiseKey(block.getType().name().toLowerCase(Locale.ROOT));
        ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.NEARBY_NOISES,
                config.noiseTicks(), () -> player.stopSound(key, SoundCategory.BLOCKS));
        if (entry == null) return false;
        player.playSound(block.getLocation().add(.5, .5, .5), key, SoundCategory.BLOCKS, config.noiseVolume(), config.noisePitch());
        return true;
    }

    private boolean flicker(Player player, HorrorConfig config) {
        var reach = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
        if (reach == null || !Double.isFinite(reach.getValue()) || player.isHandRaised()
                || player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return false;
        Location eye = player.getEyeLocation();
        List<Location> lights = nearby(player, config.flickerRange(), true).stream()
                // A margin beyond interaction reach keeps the enclosing cube out of reach too.
                .filter(b -> b.getLocation().add(.5, .5, .5).distanceSquared(eye) > Math.pow(reach.getValue() + 2, 2))
                .filter(b -> !(b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w) || !w.isWaterlogged())
                .map(Block::getLocation).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(lights, random);
        lights = List.copyOf(lights.subList(0, Math.min(config.lightCount(), lights.size())));
        if (lights.isEmpty()) return false;
        List<Location> selected = lights;
        var air = player.getServer().createBlockData("minecraft:air");
        ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.TORCH_FLICKER,
                config.flickerTicks(), () -> selected.forEach(at -> PrivateBlocks.restore(player, at)));
        if (entry == null) return false;
        for (int transition = 0; transition < config.flickers() * 2; transition++) {
            boolean hidden = transition % 2 == 0;
            Runnable change = () -> {
                if (entry.ended().get()) return;
                for (Location at : selected) {
                    if (!player.getWorld().equals(at.getWorld())) { end(entry); return; }
                    if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
                    String material = at.getBlock().getType().name().toLowerCase(Locale.ROOT);
                    boolean wet = at.getBlock().getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
                    if (hidden && HorrorDecision.light(material) && !wet) player.sendBlockChange(at, air);
                    else PrivateBlocks.restore(player, at);
                }
            };
            long tick = HorrorDecision.flickerTick(transition, config);
            if (tick == 0) change.run();
            else if (!dispatcher.scheduleTracked(player.getUniqueId(), change, tick)) { end(entry); return false; }
        }
        return true;
    }

    private boolean subliminal(Player player, HorrorConfig config, RuntimeSnapshot snapshot, MessageRegistry messages) {
        List<String> keys = snapshot.messages().lineKeys("effects.subliminal.words");
        if (keys.isEmpty()) return false;
        int index = wordIndexes.getOrDefault(player.getUniqueId(), 0);
        String key = keys.get(Math.floorMod(index, keys.size()));
        var values = Map.of("player", player.getName());
        CatalogueLines.validateLine(key, -1, snapshot.messages().resolveRaw(key, new HashSet<>(), null), values, 32, false);
        Component text = messages.render(snapshot, key, values);
        try {
            PrivateScreen screen = new PrivateScreen(player, text, new PresentationConfig.Flash("title", 0, config.subliminalTicks(), 0));
            ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.SUBLIMINAL,
                    config.subliminalTicks(), screen::restore);
            if (entry == null) return false;
            screen.show();
            wordIndexes.put(player.getUniqueId(), (index + 1) % keys.size());
            return true;
        } catch (ReflectiveOperationException failure) { return false; }
    }

    private boolean vignette(Player player, HorrorConfig config) {
        var previous = player.getWorldBorder();
        var source = previous == null ? player.getWorld().getWorldBorder() : previous;
        var world = player.getWorld();
        var border = player.getServer().createWorldBorder();
        border.setCenter(source.getCenter().getX(), source.getCenter().getZ());
        border.setSize(source.getSize());
        border.setWarningDistance(Integer.MAX_VALUE);
        border.setWarningTimeTicks(0);
        ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.RED_VIGNETTE,
                config.vignetteTicks(), () -> {
                    if (player.getWorldBorder() == border)
                        player.setWorldBorder(player.getWorld().equals(world) ? previous : null);
                });
        if (entry == null) return false;
        player.setWorldBorder(border);
        return true;
    }

    private boolean watcher(Player player, HorrorConfig config) {
        String kind = config.watcherKinds().get(random.nextInt(config.watcherKinds().size()));
        Location origin = player.getLocation();
        for (int attempt = 0; attempt < 8; attempt++) {
            double yaw = origin.getYaw() + (random.nextBoolean() ? 1 : -1) * (18 + random.nextDouble() * 12);
            double distance = config.watcherMin() + random.nextDouble() * (config.watcherMax() - config.watcherMin());
            var offset = HorrorDecision.offset(yaw, distance);
            Location at = dispatcher.apparitionGround(origin, origin.getX() + offset.x(), origin.getZ() + offset.z());
            if (at == null || at.distanceSquared(origin) < config.watcherMin() * config.watcherMin()
                    || at.distanceSquared(origin) > config.watcherMax() * config.watcherMax()) continue;
            try {
                PrivateGhost figure = PrivateGhost.animal(player, at, kind,
                        dispatcher.silverfishService().resolveEntityType(org.bukkit.NamespacedKey.minecraft(kind)));
                if (!dispatcher.safeAnimalViewer(player, figure.bounds()) || !dispatcher.clearAnimalSpace(at, figure.bounds())
                        || !dispatcher.visibleAnimal(player, figure.bounds())) continue;
                Location target = at.clone().add(0, (figure.bounds().maxY() - figure.bounds().minY()) / 2, 0);
                if (looking(player.getEyeLocation(), target, config.lookDot())) continue;
                registry().register(figure.entry());
                ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.WATCHER,
                        config.watcherTicks(), () -> {
                            watchers.remove(player.getUniqueId());
                            registry().cleanDespawn(figure.entry());
                        });
                if (entry == null) return false;
                watchers.put(player.getUniqueId(), new Watcher(target, figure.bounds(), config.lookDot(), entry));
                figure.show();
                return true;
            } catch (ReflectiveOperationException failure) { return false; }
        }
        return false;
    }
    void checkWatcher(Player player, Location to, boolean teleport) {
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        if (teleport || to == null || !to.getWorld().equals(watcher.target().getWorld())
                || !dispatcher.safeAnimalViewerAt(player, watcher.bounds(), to)
                || looking(to.clone().add(0, player.getEyeHeight(), 0), watcher.target(), watcher.threshold())) end(watcher.entry());
    }
    private boolean looking(Location eye, Location at, double threshold) {
        var direction = eye.getDirection();
        return HorrorDecision.lookedAt(at.getX() - eye.getX(), at.getY() - eye.getY(), at.getZ() - eye.getZ(),
                direction.getX(), direction.getY(), direction.getZ(), threshold);
    }

    private boolean lightning(Player player, HorrorConfig config, RuntimeSnapshot snapshot) {
        Location at = player.getLocation();
        var offset = HorrorDecision.offset(random.nextDouble() * 360, config.lightningRange());
        at.add(offset.x(), 0, offset.z());
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return false;
        var slot = snapshot.config().sounds().get(config.thunderSlot());
        try {
            PrivateGhost bolt = PrivateGhost.animal(player, at, "lightning_bolt",
                    dispatcher.silverfishService().resolveEntityType(org.bukkit.NamespacedKey.minecraft("lightning_bolt")));
            registry().register(bolt.entry());
            ActivePresentationEntry entry = dispatcher.startPresentation(player.getUniqueId(), AmbientEffectType.FAKE_LIGHTNING,
                    config.durationTicks(AmbientEffectType.FAKE_LIGHTNING, snapshot.config().sounds()), () -> {
                        registry().cleanDespawn(bolt.entry());
                        for (var layer : slot.layers()) if (!layer.isSilent()) player.stopSound(layer.key(), layer.category());
                    });
            if (entry == null) return false;
            if (!dispatcher.scheduleTracked(player.getUniqueId(), () -> registry().cleanDespawn(bolt.entry()), config.lightningTicks())) {
                end(entry); return false;
            }
            bolt.show();
            dispatcher.playSoundSlot(player, slot, config.thunderSlot(), snapshot,
                    (recipient, layer) -> recipient.playSound(at, layer.key(), layer.category(), layer.volume(), layer.pitch()));
            return true;
        } catch (ReflectiveOperationException failure) { return false; }
    }
    void forget(UUID player) { wordIndexes.remove(player); }
}
