package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for momentary private, packet-only silverfish glimpses (SB-099).
 * - Decision 0002: Packet-only fake entity strictly enforced; no real entity is ever spawned in the world.
 * - If packet construction fails, the effect is skipped and a warning is logged once per server run.
 * - Paper 26.3 has no NMS EntityType.SILVERFISH field: resolve minecraft:silverfish by registry key
 *   through Paper's CraftEntityType bridge, never by a version-dependent NMS constant name.
 * - Registered in {@link AmbientEntityRegistry} and removal packet constructed before sending spawn packet,
 *   guaranteeing cleanup on disappearance, player quit, world change, and plugin disable (T-073).
 */
public class FakeSilverfishService {

    public enum Mode {
        PACKET_ONLY,
        PREFER_PACKET,
        FORCE_PACKET
    }

    private static final AtomicBoolean WARNED_PACKET_FAILURE = new AtomicBoolean(false);

    private final AmbientEntityRegistry registry;
    private final Logger logger;
    private volatile Mode mode = Mode.PACKET_ONLY;
    private boolean packetUnavailable;

    public FakeSilverfishService(Plugin plugin, AmbientEntityRegistry registry, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "AmbientEntityRegistry must not be null");
        this.logger = logger != null ? logger : Logger.getLogger(FakeSilverfishService.class.getName());
    }

    public AmbientEntityRegistry registry() { return registry; }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Resets the once-per-run warning flag for testing purposes.
     */
    static void resetWarningFlagForTesting() {
        WARNED_PACKET_FAILURE.set(false);
    }

    /**
     * Sends a packet-only glimpse and immediately removes it through the managed registry.
     * Returns null if packet construction fails (never falls back to a real entity).
     */
    public ActiveEntityEntry spawnSilverfish(Player player, Location location) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(location, "location must not be null");
        if (packetUnavailable) {
            return null;
        }
        return trySpawnPacketSilverfish(player, location);
    }

    private ActiveEntityEntry trySpawnPacketSilverfish(Player player, Location location) {
        try {
            NamespacedKey entityKey = EntityType.SILVERFISH.getKey();
            Object silverfishType = resolveEntityType(entityKey);
            if (silverfishType == null) {
                warnOnce("Entity type not found for " + entityKey, null);
                return null;
            }

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
                warnOnce("Connection field not found on ServerPlayer", null);
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
                warnOnce("send method not found on player connection", null);
                return null;
            }

            // Create fake Silverfish entity without adding to level (net.minecraft.world.entity.monster.Silverfish)
            Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");

            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
            Method getLevelMethod = null;
            for (Method m : serverPlayer.getClass().getMethods()) {
                if ((m.getName().equals("level") || m.getName().equals("serverLevel")) && m.getParameterCount() == 0) {
                    getLevelMethod = m;
                    break;
                }
            }
            if (getLevelMethod == null) {
                warnOnce("Level getter not found on ServerPlayer", null);
                return null;
            }
            Object level = getLevelMethod.invoke(serverPlayer);

            Class<?> silverfishClass = Class.forName("net.minecraft.world.entity.monster.Silverfish");
            Constructor<?> sfConstructor = silverfishClass.getConstructor(entityTypeClass, levelClass);
            Object fishInstance = sfConstructor.newInstance(silverfishType, level);

            Method setPosMethod = fishInstance.getClass().getMethod("setPos", double.class, double.class, double.class);
            setPosMethod.invoke(fishInstance, location.getX(), location.getY(), location.getZ());

            Method getIdMethod = fishInstance.getClass().getMethod("getId");
            int entityId = (int) getIdMethod.invoke(fishInstance);
            Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
            Object spawnPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                    .getConstructor(int.class, UUID.class, double.class, double.class, double.class, float.class,
                            float.class, entityTypeClass, int.class, vector, double.class)
                    .newInstance(entityId, silverfishClass.getMethod("getUUID").invoke(fishInstance),
                            location.getX(), location.getY(), location.getZ(), location.getPitch(), location.getYaw(),
                            silverfishType, 0, vector.getField("ZERO").get(null), (double) location.getYaw());

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

            ActiveEntityEntry entry = new ActiveEntityEntry(
                    player.getUniqueId(),
                    entityId,
                    null,
                    location.getWorld() != null ? location.getWorld().getUID() : null,
                    null,
                    cleanup
            );

            sendGlimpse(entry, () -> {
                try {
                    finalSendMethod.invoke(finalConnection, spawnPacket);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });

            return entry;
        } catch (Throwable t) {
            warnOnce(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), t);
            return null;
        }
    }

    // Keeps the managed lifecycle testable without constructing NMS packets.
    void sendGlimpse(ActiveEntityEntry entry, Runnable sendSpawn) {
        registry.register(entry);
        try {
            sendSpawn.run();
        } finally {
            registry.cleanDespawn(entry);
        }
    }

    // Package-private seam: tests can exercise resolution failures without a live Bukkit registry.
    Object resolveEntityType(NamespacedKey key) throws ReflectiveOperationException {
        EntityType bukkitType = Registry.ENTITY_TYPE.get(key);
        if (bukkitType == null) {
            return null;
        }
        Class<?> craftEntityType = Class.forName("org.bukkit.craftbukkit.entity.CraftEntityType");
        // Paper's bridge resolves the NMS registry entry using bukkitType.getKey().
        return craftEntityType.getMethod("bukkitToMinecraft", EntityType.class).invoke(null, bukkitType);
    }

    private void warnOnce(String reason, Throwable cause) {
        packetUnavailable = true;
        if (WARNED_PACKET_FAILURE.compareAndSet(false, true)) {
            if (cause != null) {
                logger.log(Level.WARNING, "Packet-only fake silverfish unavailable (" + reason + "); effect disabled", cause);
            } else {
                logger.log(Level.WARNING, "Packet-only fake silverfish unavailable (" + reason + "); effect disabled");
            }
        }
    }

}
