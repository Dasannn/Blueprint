package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/** Send only shared flags to this viewer, without mutating the server entity. */
final class PrivateAnimalGlow {
    private final Player viewer;
    private final Entity animal;
    private final Object connection;
    private final Method send;
    private final Object handle;
    private final Constructor<?> packet, dataValue;

    PrivateAnimalGlow(Player viewer, Entity animal) throws ReflectiveOperationException {
        this.viewer = viewer;
        this.animal = animal;
        Object playerHandle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        connection = playerHandle.getClass().getField("connection").get(playerHandle);
        send = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        handle = animal.getClass().getMethod("getHandle").invoke(animal);
        packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                .getConstructor(int.class, List.class);
        dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue")
                .getConstructor(int.class, Class.forName("net.minecraft.network.syncher.EntityDataSerializer"), Object.class);
    }

    boolean eligible(double range) {
        return viewer.isOnline() && animal.isValid() && !animal.isDead() && !animal.isInvisible()
                && animal.getWorld().equals(viewer.getWorld()) && viewer.canSee(animal)
                && !animal.hasMetadata("vanished") && animal.getTrackedBy().contains(viewer)
                && animal.getLocation().distanceSquared(viewer.getLocation()) <= range * range;
    }

    void show() throws ReflectiveOperationException { sendFlags(true); }

    private void sendFlags(boolean glow) throws ReflectiveOperationException {
        Object data = handle.getClass().getMethod("getEntityData").invoke(handle);
        List<?> values = (List<?>) data.getClass().getMethod("packAll").invoke(data);
        if (values == null) throw new IllegalStateException("Entity metadata unavailable");
        for (Object value : values) {
            Class<?> type = value.getClass();
            if ((int) type.getMethod("id").invoke(value) != 0) continue;
            byte flags = (byte) type.getMethod("value").invoke(value);
            Object shared = dataValue.newInstance(0, type.getMethod("serializer").invoke(value),
                    glow ? CalmEffectDecision.glowingFlags(flags) : flags);
            send.invoke(connection, packet.newInstance(animal.getEntityId(), List.of(shared)));
            return;
        }
        throw new IllegalStateException("Shared entity flags unavailable");
    }

    void restore() {
        if (!viewer.isOnline() || !animal.isValid() || !animal.getWorld().equals(viewer.getWorld())
                || !animal.getTrackedBy().contains(viewer)) return;
        // Read true flags now: preserve current fire, invisibility, native glow, etc.
        try { sendFlags(false); }
        catch (ReflectiveOperationException | RuntimeException ignored) {}
    }
}
