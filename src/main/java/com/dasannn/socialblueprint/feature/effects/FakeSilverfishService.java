package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for spawning private, cosmetic silverfish per SB-040, SB-042, and Decision 0002.
 * - Decision 0002: Packet-only fake entity strictly enforced; no real entity is ever spawned in the world.
 * - If packet construction fails, the effect is skipped and a warning is logged once per server run.
 * - Client-side movement packets are sent across its short lifetime; if movement packets cannot be built,
 *   the effect degrades gracefully (remains stationary) without ever falling back to a real mob.
 * - Registered in {@link AmbientEntityRegistry} and removal packet constructed before sending spawn packet,
 *   guaranteeing cleanup on despawn timer, player quit, world change, and plugin disable (T-073).
 */
public class FakeSilverfishService {

    public enum Mode {
        PACKET_ONLY,
        PREFER_PACKET,
        FORCE_PACKET
    }

    private static final AtomicBoolean WARNED_PACKET_FAILURE = new AtomicBoolean(false);

    private final Plugin plugin;
    private final AmbientEntityRegistry registry;
    private final Logger logger;
    private volatile Mode mode = Mode.PACKET_ONLY;

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
     * Resets the once-per-run warning flag for testing purposes.
     */
    static void resetWarningFlagForTesting() {
        WARNED_PACKET_FAILURE.set(false);
    }

    /**
     * Spawns a packet-only silverfish effect for the specified player and schedules its removal.
     * Returns null if packet construction fails (never falls back to a real entity).
     */
    public ActiveEntityEntry spawnSilverfish(Player player, Location location, int durationTicks) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(location, "location must not be null");
        long delayTicks = Math.max(1L, durationTicks);

        return trySpawnPacketSilverfish(player, location, delayTicks);
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
                warnOnce("Level getter not found on ServerPlayer", null);
                return null;
            }
            Object level = getLevelMethod.invoke(serverPlayer);

            Class<?> silverfishClass = Class.forName("net.minecraft.world.entity.monster.Silverfish");
            Constructor<?> sfConstructor = silverfishClass.getConstructor(entityTypeClass, levelClass);
            Object fishInstance = sfConstructor.newInstance(silverfishType, level);

            Method setPosMethod = fishInstance.getClass().getMethod("setPos", double.class, double.class, double.class);
            setPosMethod.invoke(fishInstance, location.getX(), location.getY(), location.getZ());

            // Match constructor parameter count strictly to 1 (Finding 1)
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
                    if (c.getParameterCount() == 1 && c.getParameterTypes()[0].getSimpleName().equals("Entity")) {
                        packetConstructor = c;
                        break;
                    }
                }
            }
            if (packetConstructor == null) {
                warnOnce("ClientboundAddEntityPacket 1-parameter constructor not found", null);
                return null;
            }

            Object spawnPacket = packetConstructor.newInstance(fishInstance);

            Method getIdMethod = fishInstance.getClass().getMethod("getId");
            int entityId = (int) getIdMethod.invoke(fishInstance);

            Class<?> removeClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Constructor<?> removeConstructor = removeClass.getConstructor(int[].class);
            Object removePacket = removeConstructor.newInstance((Object) new int[]{entityId});

            final Method finalSendMethod = sendMethod;
            final Object finalConnection = connection;
            final AtomicReference<BukkitTask> moveTaskRef = new AtomicReference<>();

            Runnable cleanup = () -> {
                BukkitTask moveTask = moveTaskRef.get();
                if (moveTask != null && !moveTask.isCancelled()) {
                    try {
                        moveTask.cancel();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    finalSendMethod.invoke(finalConnection, removePacket);
                } catch (Exception ignored) {
                }
            };

            ActiveEntityHolder holder = new ActiveEntityHolder();
            BukkitTask despawnTask = null;
            if (plugin != null && plugin.isEnabled()) {
                despawnTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (holder.entry != null) {
                        registry.cleanDespawn(holder.entry);
                    }
                }, delayTicks);
            }

            ActiveEntityEntry entry = new ActiveEntityEntry(
                    player.getUniqueId(),
                    entityId,
                    null,
                    location.getWorld() != null ? location.getWorld().getUID() : null,
                    despawnTask,
                    cleanup
            );
            holder.entry = entry;

            // Register in registry before sending spawn packet (Finding 2)
            registry.register(entry);

            // Send spawn packet and schedule movement; wrap setup so any failure cleans up immediately (Finding 2)
            try {
                sendMethod.invoke(connection, spawnPacket);
                scheduleMovement(player, connection, sendMethod, fishInstance, entityId, location, delayTicks, moveTaskRef);
            } catch (Throwable t) {
                registry.cleanDespawn(entry);
                throw t;
            }

            return entry;
        } catch (Throwable t) {
            warnOnce(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), t);
            return null;
        }
    }

    private void scheduleMovement(
            Player player,
            Object connection,
            Method sendMethod,
            Object fishInstance,
            int entityId,
            Location startLoc,
            long delayTicks,
            AtomicReference<BukkitTask> moveTaskRef
    ) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        // Try to reflectively find relative move or teleport constructor (Finding 3)
        final Method setPosMethod;
        final Constructor<?> teleportConstructor;
        final Constructor<?> movePosConstructor;

        Constructor<?> tConst = null;
        Method sPos = null;
        try {
            sPos = fishInstance.getClass().getMethod("setPos", double.class, double.class, double.class);
            Class<?> teleportClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            for (Constructor<?> c : teleportClass.getConstructors()) {
                if (c.getParameterCount() == 1 && (c.getParameterTypes()[0].isAssignableFrom(fishInstance.getClass()) || c.getParameterTypes()[0].getSimpleName().equals("Entity"))) {
                    tConst = c;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        setPosMethod = sPos;
        teleportConstructor = tConst;

        Constructor<?> mConst = null;
        try {
            Class<?> movePosClass = Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Pos");
            for (Constructor<?> c : movePosClass.getConstructors()) {
                if (c.getParameterCount() == 5
                        && c.getParameterTypes()[0] == int.class
                        && c.getParameterTypes()[1] == short.class
                        && c.getParameterTypes()[2] == short.class
                        && c.getParameterTypes()[3] == short.class
                        && c.getParameterTypes()[4] == boolean.class) {
                    mConst = c;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        movePosConstructor = mConst;

        if (teleportConstructor == null && movePosConstructor == null) {
            // Cannot build movement packet: degrade gracefully, silverfish stays still (Finding 3)
            return;
        }

        long moveInterval = Math.max(4L, delayTicks / 5L);
        double[] currentPos = new double[]{startLoc.getX(), startLoc.getY(), startLoc.getZ()};

        BukkitTask moveTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int step = 0;

            @Override
            public void run() {
                step++;
                if (player == null || !player.isOnline() || !registry.hasActiveEntities(player.getUniqueId())) {
                    BukkitTask task = moveTaskRef.get();
                    if (task != null) {
                        try {
                            task.cancel();
                        } catch (Exception ignored) {
                        }
                    }
                    return;
                }

                try {
                    Location pLoc = player.getLocation();
                    double dirX = pLoc.getX() - currentPos[0];
                    double dirZ = pLoc.getZ() - currentPos[2];
                    double dist = Math.sqrt(dirX * dirX + dirZ * dirZ);
                    double stepDist = 0.35;

                    double deltaX = (dist > 0.3) ? (dirX / dist) * stepDist : 0.0;
                    double deltaZ = (dist > 0.3) ? (dirZ / dist) * stepDist : 0.0;

                    // Small lateral scurrying jitter
                    deltaX += (Math.random() - 0.5) * 0.1;
                    deltaZ += (Math.random() - 0.5) * 0.1;

                    currentPos[0] += deltaX;
                    currentPos[2] += deltaZ;

                    if (teleportConstructor != null && setPosMethod != null) {
                        setPosMethod.invoke(fishInstance, currentPos[0], currentPos[1], currentPos[2]);
                        Object packet = teleportConstructor.newInstance(fishInstance);
                        sendMethod.invoke(connection, packet);
                    } else if (movePosConstructor != null) {
                        short xa = (short) Math.clamp(Math.round(deltaX * 4096.0), Short.MIN_VALUE, Short.MAX_VALUE);
                        short ya = 0;
                        short za = (short) Math.clamp(Math.round(deltaZ * 4096.0), Short.MIN_VALUE, Short.MAX_VALUE);
                        Object packet = movePosConstructor.newInstance(entityId, xa, ya, za, true);
                        sendMethod.invoke(connection, packet);
                    }
                } catch (Throwable ignored) {
                    // Degrade gracefully on movement failure without logging or fallback
                }
            }
        }, moveInterval, moveInterval);

        moveTaskRef.set(moveTask);
    }

    private void warnOnce(String reason, Throwable cause) {
        if (WARNED_PACKET_FAILURE.compareAndSet(false, true)) {
            if (cause != null) {
                logger.log(Level.WARNING, "Packet-only fake silverfish unavailable (" + reason + "); effect disabled", cause);
            } else {
                logger.log(Level.WARNING, "Packet-only fake silverfish unavailable (" + reason + "); effect disabled");
            }
        }
    }

    private static class ActiveEntityHolder {
        ActiveEntityEntry entry;
    }
}
