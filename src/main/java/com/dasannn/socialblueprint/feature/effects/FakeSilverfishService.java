package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for spawning private, cosmetic silverfish per SB-040, SB-042, and Decision 0002.
 * - Decision 0002: Packet-only fake preferred so no real entity exists in the world.
 * - Paper 26.3 Notes §7: Entity visibility API fallback with full guards (speed III,
 *   no damage, invulnerable, uncollidable, silent, not persistent, removed on timer).
 * - Registered in {@link AmbientEntityRegistry} and guaranteed to clean up on despawn timer,
 *   quit, world change, and plugin disable (T-073).
 */
public class FakeSilverfishService {

    public enum Mode {
        PREFER_PACKET,
        FORCE_PACKET,
        FORCE_PAPER_VISIBILITY
    }

    private final Plugin plugin;
    private final AmbientEntityRegistry registry;
    private final Logger logger;
    private volatile Mode mode = Mode.PREFER_PACKET;
    private static final AtomicInteger FAKE_ENTITY_ID_GEN = new AtomicInteger(1_000_000);

    public FakeSilverfishService(Plugin plugin, AmbientEntityRegistry registry, Logger logger) {
        this.plugin = plugin;
        this.registry = Objects.requireNonNull(registry, "AmbientEntityRegistry must not be null");
        this.logger = logger != null ? logger : Logger.getLogger(FakeSilverfishService.class.getName());
    }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Spawns a silverfish effect for the specified player and schedules its removal.
     */
    public ActiveEntityEntry spawnSilverfish(Player player, Location location, int durationTicks) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(location, "location must not be null");
        long delayTicks = Math.max(1L, durationTicks);

        ActiveEntityEntry entry = null;

        if (mode != Mode.FORCE_PAPER_VISIBILITY) {
            entry = trySpawnPacketSilverfish(player, location, delayTicks);
        }

        if (entry == null && mode != Mode.FORCE_PACKET) {
            entry = spawnPaperVisibilitySilverfish(player, location, delayTicks);
        }

        if (entry != null) {
            registry.register(entry);
        }

        return entry;
    }

    private ActiveEntityEntry trySpawnPacketSilverfish(Player player, Location location, long delayTicks) {
        try {
            // Reflectively access CraftPlayer -> ServerPlayer -> connection
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandleMethod.invoke(player);

            Field connectionField = null;
            for (Field f : serverPlayer.getClass().getFields()) {
                if (f.getName().equals("connection") || f.getType().getSimpleName().contains("PacketListener")) {
                    connectionField = f;
                    break;
                }
            }
            if (connectionField == null) {
                for (Field f : serverPlayer.getClass().getDeclaredFields()) {
                    if (f.getName().equals("connection") || f.getType().getSimpleName().contains("PacketListener")) {
                        connectionField = f;
                        connectionField.setAccessible(true);
                        break;
                    }
                }
            }
            if (connectionField == null) {
                return null;
            }
            Object connection = connectionField.get(serverPlayer);

            Method sendMethod = null;
            for (Method m : connection.getClass().getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 1) {
                    sendMethod = m;
                    break;
                }
            }
            if (sendMethod == null) {
                return null;
            }

            // Create fake Silverfish entity without adding to level (net.minecraft.world.entity.monster.Silverfish)
            Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");
            Field silverfishField = entityTypeClass.getField("SILVERFISH");
            Object silverfishType = silverfishField.get(null);

            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
            Method getLevelMethod = null;
            for (Method m : serverPlayer.getClass().getMethods()) {
                if ((m.getName().equals("level") || m.getName().equals("serverLevel")) && m.getParameterCount() == 0) {
                    getLevelMethod = m;
                    break;
                }
            }
            if (getLevelMethod == null) {
                return null;
            }
            Object level = getLevelMethod.invoke(serverPlayer);

            Class<?> silverfishClass = Class.forName("net.minecraft.world.entity.monster.Silverfish");
            Constructor<?> sfConstructor = silverfishClass.getConstructor(entityTypeClass, levelClass);
            Object fishInstance = sfConstructor.newInstance(silverfishType, level);

            Method setPosMethod = fishInstance.getClass().getMethod("setPos", double.class, double.class, double.class);
            setPosMethod.invoke(fishInstance, location.getX(), location.getY(), location.getZ());

            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            Constructor<?> packetConstructor = null;
            for (Constructor<?> c : packetClass.getConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isAssignableFrom(silverfishClass)) {
                    packetConstructor = c;
                    break;
                }
            }
            if (packetConstructor == null) {
                for (Constructor<?> c : packetClass.getConstructors()) {
                    if (c.getParameterCount() >= 1 && c.getParameterTypes()[0].getSimpleName().equals("Entity")) {
                        packetConstructor = c;
                        break;
                    }
                }
            }
            if (packetConstructor == null) {
                return null;
            }

            Object spawnPacket = packetConstructor.newInstance(fishInstance);
            sendMethod.invoke(connection, spawnPacket);

            Method getIdMethod = fishInstance.getClass().getMethod("getId");
            int entityId = (int) getIdMethod.invoke(fishInstance);

            Class<?> removeClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Constructor<?> removeConstructor = removeClass.getConstructor(int[].class);
            Object removePacket = removeConstructor.newInstance((Object) new int[]{entityId});

            final Method finalSendMethod = sendMethod;
            final Object finalConnection = connection;
            Runnable cleanup = () -> {
                try {
                    finalSendMethod.invoke(finalConnection, removePacket);
                } catch (Exception ignored) {
                }
            };

            ActiveEntityHolder holder = new ActiveEntityHolder();
            BukkitTask task = null;
            if (plugin != null && plugin.isEnabled()) {
                task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (holder.entry != null) {
                        registry.cleanDespawn(holder.entry);
                    }
                }, delayTicks);
            }

            ActiveEntityEntry entry = new ActiveEntityEntry(
                    player.getUniqueId(),
                    entityId,
                    null,
                    location.getWorld().getUID(),
                    task,
                    cleanup
            );
            holder.entry = entry;
            return entry;
        } catch (Throwable t) {
            logger.log(Level.FINE, "Packet silverfish reflection unavailable, falling back to Paper visibility", t);
            return null;
        }
    }

    private ActiveEntityEntry spawnPaperVisibilitySilverfish(Player player, Location location, long delayTicks) {
        if (location.getWorld() == null) {
            return null;
        }

        // Spawn with visibility falseByDefault per Paper Notes §6, §7
        Silverfish fish = location.getWorld().spawn(location, Silverfish.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setCollidable(false);
            e.setSilent(true);
            e.setTarget(null);

            AttributeInstance attack = e.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                attack.setBaseValue(0.0);
            }

            // Speed III (amplifier 2) with no particles
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) delayTicks + 40, 2, false, false));
        });

        // Show entity only to target player (private per SB-041)
        if (plugin != null) {
            player.showEntity(plugin, fish);
        }

        Runnable cleanup = () -> {
            try {
                if (plugin != null) {
                    player.hideEntity(plugin, fish);
                }
            } catch (Exception ignored) {
            }
            try {
                if (fish.isValid()) {
                    fish.remove();
                }
            } catch (Exception ignored) {
            }
        };

        ActiveEntityHolder holder = new ActiveEntityHolder();
        BukkitTask task = null;
        if (plugin != null && plugin.isEnabled()) {
            task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (holder.entry != null) {
                    registry.cleanDespawn(holder.entry);
                }
            }, delayTicks);
        }

        ActiveEntityEntry entry = new ActiveEntityEntry(
                player.getUniqueId(),
                fish.getEntityId(),
                fish,
                location.getWorld().getUID(),
                task,
                cleanup
        );
        holder.entry = entry;
        return entry;
    }

    private static class ActiveEntityHolder {
        ActiveEntityEntry entry;
    }
}
