package com.dasannn.socialblueprint.feature.effects;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Unregistered NMS text display: zero-size, stationary, no skin/profile/tab identity. */
final class PrivateGhost {
    private final Object connection;
    private final Method send;
    private final Object spawn, metadata, remove;
    private final ActiveEntityEntry entry;

    PrivateGhost(Player player, Location at, Component label, Object entityType) throws ReflectiveOperationException {
        if (entityType == null) throw new IllegalStateException("Text display unavailable");
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        connection = handle.getClass().getField("connection").get(handle);
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
        send = connection.getClass().getMethod("send", packet);
        Class<?> type = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?> level = Class.forName("net.minecraft.world.level.Level");
        Class<?> display = Class.forName("net.minecraft.world.entity.Display$TextDisplay");
        Object entity = display.getConstructor(type, level).newInstance(entityType,
                handle.getClass().getMethod("level").invoke(handle));
        display.getMethod("setPos", double.class, double.class, double.class).invoke(entity, at.getX(), at.getY(), at.getZ());
        display.getMethod("setUUID", UUID.class).invoke(entity, UUID.randomUUID());
        display.getMethod("setNoGravity", boolean.class).invoke(entity, true);
        Class<?> billboard = Class.forName("net.minecraft.world.entity.Display$BillboardConstraints");
        display.getMethod("setBillboardConstraints", billboard).invoke(entity, billboard.getField("CENTER").get(null));
        display.getMethod("setTextOpacity", byte.class).invoke(entity, (byte) 160);
        Object text = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                .getMethod("asVanilla", Component.class).invoke(null, label);
        display.getMethod("setText", Class.forName("net.minecraft.network.chat.Component")).invoke(entity, text);
        int id = (int) display.getMethod("getId").invoke(entity);
        Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
        spawn = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                .getConstructor(int.class, UUID.class, double.class, double.class, double.class, float.class,
                        float.class, type, int.class, vector, double.class)
                .newInstance(id, display.getMethod("getUUID").invoke(entity), at.getX(), at.getY(), at.getZ(),
                        0F, 0F, entityType, 0, vector.getField("ZERO").get(null), 0D);
        Object data = display.getMethod("getEntityData").invoke(entity);
        Object values = data.getClass().getMethod("packAll").invoke(data);
        metadata = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                .getConstructor(int.class, List.class).newInstance(id, values);
        remove = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                .getConstructor(int[].class).newInstance((Object) new int[]{id});
        entry = new ActiveEntityEntry(player.getUniqueId(), id, null, at.getWorld().getUID(), null, this::remove);
    }

    ActiveEntityEntry entry() { return entry; }
    void show() throws ReflectiveOperationException { send.invoke(connection, spawn); send.invoke(connection, metadata); }
    private void remove() {
        try { send.invoke(connection, remove); }
        catch (ReflectiveOperationException ignored) {} // disconnected viewers retain no fake
    }
}
