package com.dasannn.socialblueprint.feature.effects;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Unregistered packet visuals, sharing spawn/metadata/removal and managed cleanup. */
final class PrivateGhost {
    private final Object connection;
    private final Method send;
    private final Object spawn, metadata, remove;
    private final ActiveEntityEntry entry;
    private final SereneEpisode.Bounds bounds;

    PrivateGhost(Player player, Location at, Component label, Object entityType) throws ReflectiveOperationException {
        this(player, at, label, entityType, null);
    }

    static PrivateGhost animal(Player viewer, Location at, String kind, Object type) throws ReflectiveOperationException {
        return new PrivateGhost(viewer, at, null, type, kind);
    }

    private PrivateGhost(Player player, Location at, Component label, Object entityType, String animal) throws ReflectiveOperationException {
        if (entityType == null) throw new IllegalStateException("Text display unavailable");
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        connection = handle.getClass().getField("connection").get(handle);
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
        send = connection.getClass().getMethod("send", packet);
        Class<?> type = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?> level = Class.forName("net.minecraft.world.level.Level");
        boolean hostile = animal != null && animal.contains(":");
        Class<?> display = hostile ? null : Class.forName(animal == null ? "net.minecraft.world.entity.Display$TextDisplay" : switch (animal) {
            case "mannequin" -> "net.minecraft.world.entity.decoration.Mannequin";
            case "cat" -> "net.minecraft.world.entity.animal.feline.Cat";
            case "fox" -> "net.minecraft.world.entity.animal.fox.Fox";
            case "wolf" -> "net.minecraft.world.entity.animal.wolf.Wolf";
            default -> throw new IllegalArgumentException(animal);
        });
        Object world = handle.getClass().getMethod("level").invoke(handle);
        Object entity = hostile ? createMob(entityType, world)
                : display.getConstructor(type, level).newInstance(entityType, world);
        display = entity.getClass();
        display.getMethod("setPos", double.class, double.class, double.class).invoke(entity, at.getX(), at.getY(), at.getZ());
        display.getMethod("setUUID", UUID.class).invoke(entity, UUID.randomUUID());
        display.getMethod("setNoGravity", boolean.class).invoke(entity, true);
        Object box = display.getMethod("getBoundingBox").invoke(entity);
        Class<?> boxType = box.getClass();
        bounds = new SereneEpisode.Bounds(boxType.getField("minX").getDouble(box), boxType.getField("minY").getDouble(box),
                boxType.getField("minZ").getDouble(box), boxType.getField("maxX").getDouble(box),
                boxType.getField("maxY").getDouble(box), boxType.getField("maxZ").getDouble(box));
        if (animal != null) {
            if (animal.equals("mannequin")) {
                // Constructor default profile only; no victim identity or profile lookup.
                display.getMethod("setImmovable", boolean.class).invoke(entity, true);
                display.getMethod("setHideDescription", boolean.class).invoke(entity, true);
            } else display.getMethod("setNoAi", boolean.class).invoke(entity, true);
            display.getMethod("setSilent", boolean.class).invoke(entity, true);
            display.getMethod("setYRot", float.class).invoke(entity, at.getYaw());
            display.getMethod("setYHeadRot", float.class).invoke(entity, at.getYaw());
        } else {
            Class<?> billboard = Class.forName("net.minecraft.world.entity.Display$BillboardConstraints");
            display.getMethod("setBillboardConstraints", billboard).invoke(entity, billboard.getField("CENTER").get(null));
            display.getMethod("setTextOpacity", byte.class).invoke(entity, (byte) 160);
            Object text = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                    .getMethod("asVanilla", Component.class).invoke(null, label);
            display.getMethod("setText", Class.forName("net.minecraft.network.chat.Component")).invoke(entity, text);
        }
        int id = (int) display.getMethod("getId").invoke(entity);
        Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
        spawn = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                .getConstructor(int.class, UUID.class, double.class, double.class, double.class, float.class,
                        float.class, type, int.class, vector, double.class)
                .newInstance(id, display.getMethod("getUUID").invoke(entity), at.getX(), at.getY(), at.getZ(),
                        at.getPitch(), at.getYaw(), entityType, 0, vector.getField("ZERO").get(null), (double) at.getYaw());
        Object data = display.getMethod("getEntityData").invoke(entity);
        Object values = data.getClass().getMethod("packAll").invoke(data);
        metadata = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                .getConstructor(int.class, List.class).newInstance(id, values);
        remove = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                .getConstructor(int[].class).newInstance((Object) new int[]{id});
        entry = new ActiveEntityEntry(player.getUniqueId(), id, null, at.getWorld().getUID(), null, this::remove);
    }

    static final class MobUnavailableException extends IllegalStateException {
        MobUnavailableException() { super("Mob factory returned no entity"); }
    }

    static Object createMob(Object type, Object world) throws ReflectiveOperationException {
        // Use the registry type's factory, rather than version-dependent hostile class names.
        for (Method method : type.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("create") || parameters.length != 2
                    || !parameters[0].isInstance(world) || !parameters[1].isEnum()
                    || !parameters[1].getSimpleName().equals("EntitySpawnReason")) continue;
            Object reason = parameters[1].getField("COMMAND").get(null);
            Object entity = method.invoke(type, world, reason);
            if (entity == null) throw new MobUnavailableException();
            return entity;
        }
        throw new NoSuchMethodException("EntityType.create(Level, EntitySpawnReason)");
    }

    ActiveEntityEntry entry() { return entry; }
    SereneEpisode.Bounds bounds() { return bounds; }
    void show() throws ReflectiveOperationException { send.invoke(connection, spawn); send.invoke(connection, metadata); }
    private void remove() {
        try { send.invoke(connection, remove); }
        catch (ReflectiveOperationException ignored) {} // disconnected viewers retain no fake
    }
}
