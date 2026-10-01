package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AmbientEntityRegistryTest {

    private AmbientEntityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AmbientEntityRegistry();
    }

    private Entity mockEntity(UUID uuid) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("equals".equals(name) && method.getParameterCount() == 1) return proxy == args[0];
            if ("hashCode".equals(name) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
            if ("toString".equals(name) && method.getParameterCount() == 0) return "MockEntity-" + uuid;
            if ("getUniqueId".equals(name)) return uuid;
            if ("isValid".equals(name)) return true;
            if ("remove".equals(name)) return null;
            return defaultValue(method.getReturnType());
        };
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                handler
        );
    }

    private BukkitTask mockTask(AtomicBoolean cancelled) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("equals".equals(name) && method.getParameterCount() == 1) return proxy == args[0];
            if ("hashCode".equals(name) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
            if ("toString".equals(name) && method.getParameterCount() == 0) return "MockBukkitTask";
            if ("cancel".equals(name)) {
                cancelled.set(true);
                return null;
            }
            if ("isCancelled".equals(name)) {
                return cancelled.get();
            }
            if ("getTaskId".equals(name)) return 100;
            return defaultValue(method.getReturnType());
        };
        return (BukkitTask) Proxy.newProxyInstance(
                BukkitTask.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                handler
        );
    }

    @Test
    @DisplayName("T-073 / SB-042 Trigger 1: Entity is cleaned on despawn timer with zero survivors")
    void cleanedOnDespawnTimer() {
        UUID playerId = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        Entity entity = mockEntity(entityUuid);
        AtomicBoolean taskCancelled = new AtomicBoolean(false);
        AtomicBoolean cleanupActionRan = new AtomicBoolean(false);

        ActiveEntityEntry entry = new ActiveEntityEntry(
                playerId,
                42,
                entity,
                UUID.randomUUID(),
                mockTask(taskCancelled),
                () -> cleanupActionRan.set(true)
        );

        registry.register(entry);
        assertThat(registry.getActiveCount()).isEqualTo(1);
        assertThat(registry.hasActiveEntities(playerId)).isTrue();
        assertThat(registry.isManaged(entity)).isTrue();

        // Despawn timer triggers
        registry.cleanDespawn(entry);

        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(registry.hasActiveEntities(playerId)).isFalse();
        assertThat(registry.isManaged(entity)).isFalse();
        assertThat(taskCancelled.get()).isTrue();
        assertThat(cleanupActionRan.get()).isTrue();
    }

    @Test
    @DisplayName("T-073 / SB-042 Trigger 2: Entities for a quitting player are cleaned with zero survivors")
    void cleanedOnPlayerQuit() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        AtomicBoolean task1Cancelled = new AtomicBoolean(false);
        AtomicBoolean cleanup1Ran = new AtomicBoolean(false);
        Entity entity1 = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry1 = new ActiveEntityEntry(
                player1, 101, entity1, UUID.randomUUID(), mockTask(task1Cancelled), () -> cleanup1Ran.set(true)
        );

        AtomicBoolean task2Cancelled = new AtomicBoolean(false);
        AtomicBoolean cleanup2Ran = new AtomicBoolean(false);
        Entity entity2 = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry2 = new ActiveEntityEntry(
                player2, 102, entity2, UUID.randomUUID(), mockTask(task2Cancelled), () -> cleanup2Ran.set(true)
        );

        registry.register(entry1);
        registry.register(entry2);
        assertThat(registry.getActiveCount()).isEqualTo(2);

        // Player 1 quits
        registry.cleanForPlayer(player1);

        assertThat(registry.hasActiveEntities(player1)).isFalse();
        assertThat(registry.isManaged(entity1)).isFalse();
        assertThat(task1Cancelled.get()).isTrue();
        assertThat(cleanup1Ran.get()).isTrue();

        // Player 2 still active
        assertThat(registry.hasActiveEntities(player2)).isTrue();
        assertThat(registry.isManaged(entity2)).isTrue();
        assertThat(registry.getActiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-073 / SB-042 Trigger 3: Entities for a player changing worlds are cleaned with zero survivors")
    void cleanedOnPlayerWorldChange() {
        UUID player = UUID.randomUUID();
        AtomicBoolean taskCancelled = new AtomicBoolean(false);
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        Entity entity = mockEntity(UUID.randomUUID());

        ActiveEntityEntry entry = new ActiveEntityEntry(
                player, 201, entity, UUID.randomUUID(), mockTask(taskCancelled), () -> cleanupRan.set(true)
        );

        registry.register(entry);
        assertThat(registry.getActiveCount()).isEqualTo(1);

        // World change triggers
        registry.cleanForPlayerWorldChange(player);

        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(registry.hasActiveEntities(player)).isFalse();
        assertThat(registry.isManaged(entity)).isFalse();
        assertThat(taskCancelled.get()).isTrue();
        assertThat(cleanupRan.get()).isTrue();
    }

    @Test
    @DisplayName("T-073 / SB-042 Trigger 4: All entities across all players are cleaned on plugin disable")
    void cleanedOnPluginDisable() {
        AtomicInteger cleanupsRan = new AtomicInteger(0);
        int totalEntities = 5;

        for (int i = 0; i < totalEntities; i++) {
            UUID pid = UUID.randomUUID();
            Entity ent = mockEntity(UUID.randomUUID());
            AtomicBoolean cancelled = new AtomicBoolean(false);
            ActiveEntityEntry entry = new ActiveEntityEntry(
                    pid, 300 + i, ent, UUID.randomUUID(), mockTask(cancelled), cleanupsRan::incrementAndGet
            );
            registry.register(entry);
        }

        assertThat(registry.getActiveCount()).isEqualTo(totalEntities);

        // Plugin disable invokes cleanAll
        registry.cleanAll();

        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(cleanupsRan.get()).isEqualTo(totalEntities);
    }

    @Test
    @DisplayName("T-073: Repeated player cleanup leaves no active fake")
    void repeatedPlayerCleanupIsSafe() {
        UUID player = UUID.randomUUID();
        AtomicBoolean taskCancelled = new AtomicBoolean(false);
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        Entity entity = mockEntity(UUID.randomUUID());

        ActiveEntityEntry entry = new ActiveEntityEntry(
                player, 401, entity, UUID.randomUUID(), mockTask(taskCancelled), () -> cleanupRan.set(true)
        );

        registry.register(entry);
        assertThat(registry.getActiveCount()).isEqualTo(1);

        // Repeated player cleanup is harmless
        registry.cleanForPlayer(player);
        registry.cleanForPlayer(player);

        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(registry.hasActiveEntities(player)).isFalse();
        assertThat(registry.isManaged(entity)).isFalse();
        assertThat(taskCancelled.get()).isTrue();
        assertThat(cleanupRan.get()).isTrue();
    }

    @Test
    @DisplayName("T-073: Null-safe operations and unmanaged entity queries")
    void nullSafeAndUnmanaged() {
        assertThat(registry.isManaged(null)).isFalse();
        assertThat(registry.hasActiveEntities(null)).isFalse();

        Entity unmanaged = mockEntity(UUID.randomUUID());
        assertThat(registry.isManaged(unmanaged)).isFalse();

        // Null cleans do not throw
        registry.cleanDespawn(null);
        registry.cleanForPlayer(null);
        registry.cleanForPlayerWorldChange(null);
        registry.unregister(null);
        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == char.class) return '\0';
        return null;
    }
}
