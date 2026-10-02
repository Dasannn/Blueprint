package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class FakeSilverfishServiceTest {

    private AmbientEntityRegistry registry;
    private Logger logger;
    private List<LogRecord> logRecords;
    private FakeSilverfishService service;
    private World mockWorld;
    private Player mockPlayer;
    private Location mockLocation;
    private final AtomicInteger worldSpawnCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        FakeSilverfishService.resetWarningFlagForTesting();
        registry = new AmbientEntityRegistry();

        logRecords = new ArrayList<>();
        logger = Logger.getLogger("FakeSilverfishServiceTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() throws SecurityException {}
        });

        service = new FakeSilverfishService(null, registry, logger);

        UUID worldUid = UUID.randomUUID();
        mockWorld = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("equals".equals(m) && method.getParameterCount() == 1) return proxy == args[0];
                    if ("hashCode".equals(m) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
                    if ("getUID".equals(m)) return worldUid;
                    if ("getName".equals(m)) return "test_world";
                    if ("spawn".equals(m)) {
                        worldSpawnCount.incrementAndGet();
                        throw new AssertionError("World#spawn must NEVER be called per Decision 0002 and Finding 1!");
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        UUID playerUid = UUID.randomUUID();
        mockLocation = new Location(mockWorld, 10, 64, 10);
        mockPlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("equals".equals(m) && method.getParameterCount() == 1) return proxy == args[0];
                    if ("hashCode".equals(m) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
                    if ("getUniqueId".equals(m)) return playerUid;
                    if ("getName".equals(m)) return "TestPlayer";
                    if ("isOnline".equals(m)) return true;
                    if ("getLocation".equals(m)) return mockLocation;
                    if ("getWorld".equals(m)) return mockWorld;
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @Test
    void phantomIsRegisteredBeforeSpawnAndRemovedInTheSameCall() {
        List<String> packets = new ArrayList<>();
        ActiveEntityEntry entry = new ActiveEntityEntry(mockPlayer.getUniqueId(), 555, null,
                mockWorld.getUID(), null, () -> packets.add("remove"));
        service.sendGlimpse(entry, () -> {
            assertThat(registry.hasActiveEntities(mockPlayer.getUniqueId())).isTrue();
            packets.add("spawn");
        });
        assertThat(packets).containsExactly("spawn", "remove");
        assertThat(registry.getActiveCount()).isZero();
        assertThat(worldSpawnCount.get()).isZero();
    }

    @Test
    void interruptedGlimpseStillRemovesTheFake() {
        AtomicBoolean removed = new AtomicBoolean();
        ActiveEntityEntry entry = new ActiveEntityEntry(mockPlayer.getUniqueId(), 555, null,
                mockWorld.getUID(), null, () -> removed.set(true));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sendGlimpse(entry, () -> {
            throw new IllegalStateException("send failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(removed.get()).isTrue();
        assertThat(registry.getActiveCount()).isZero();
    }

    @Test
    void entityResolutionUsesSilverfishKeyAndNoNamedNmsField() throws Exception {
        List<NamespacedKey> attemptedKeys = new ArrayList<>();
        service = new FakeSilverfishService(null, registry, logger) {
            @Override
            Object resolveEntityType(NamespacedKey key) {
                attemptedKeys.add(key);
                return null;
            }
        };

        assertThat(service.spawnSilverfish(mockPlayer, mockLocation)).isNull();
        assertThat(attemptedKeys).containsExactly(NamespacedKey.minecraft("silverfish"));

        String source = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects/FakeSilverfishService.java"));
        assertThat(source).contains("EntityType.SILVERFISH.getKey()", "Registry.ENTITY_TYPE.get(key)",
                "getMethod(\"bukkitToMinecraft\", EntityType.class)");
        assertThat(source).doesNotContain("getField(\"SILVERFISH\")", "getDeclaredField(\"SILVERFISH\")");
        assertThat(source.replaceAll("\\s+", "")).contains(
                ".getConstructor(int.class,UUID.class,double.class,double.class,double.class,float.class,float.class,entityTypeClass,int.class,vector,double.class)")
                .doesNotContain("c.getParameterCount()==1");
    }

    @Test
    void missingEntityResolutionDisablesEffectAndLogsOnce() {
        assertResolutionFailureDisablesEffect(false);
    }

    @Test
    void throwingEntityResolutionDisablesEffectAndLogsOnce() {
        assertResolutionFailureDisablesEffect(true);
    }

    private void assertResolutionFailureDisablesEffect(boolean throwsFailure) {
        AtomicInteger attempts = new AtomicInteger();
        service = new FakeSilverfishService(null, registry, logger) {
            @Override
            Object resolveEntityType(NamespacedKey key) throws ReflectiveOperationException {
                attempts.incrementAndGet();
                if (throwsFailure) {
                    throw new ReflectiveOperationException("registry resolution failed");
                }
                return null;
            }
        };

        assertThat(service.spawnSilverfish(mockPlayer, mockLocation)).isNull();
        assertThat(service.spawnSilverfish(mockPlayer, mockLocation)).isNull();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(logRecords).hasSize(1);
        assertThat(logRecords.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(logRecords.get(0).getMessage()).contains("effect disabled",
                throwsFailure ? "registry resolution failed" : "minecraft:silverfish");
        assertThat(registry.getActiveCount()).isZero();
        assertThat(worldSpawnCount.get()).isZero();
    }

    @Test
    @DisplayName("Finding 1: When packet path fails, returns null, NEVER spawns real entity, and never calls World#spawn")
    void packetFailureReturnsNullAndNeverSpawnsRealEntity() {
        ActiveEntityEntry entry = service.spawnSilverfish(mockPlayer, mockLocation);

        // Must return null — never an entity
        assertThat(entry).isNull();
        // World#spawn was never called
        assertThat(worldSpawnCount.get()).isEqualTo(0);
        // Registry contains zero entities
        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Finding 1: When packet path fails, logs one warning naming reason, once per server run")
    void packetFailureLogsWarningOncePerServerRun() {
        // First spawn attempt
        ActiveEntityEntry entry1 = service.spawnSilverfish(mockPlayer, mockLocation);
        assertThat(entry1).isNull();

        long warningCount = logRecords.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .count();
        assertThat(warningCount).isEqualTo(1);
        assertThat(logRecords.get(0).getMessage()).contains("Packet-only fake silverfish unavailable");

        // Second spawn attempt on the same server run
        ActiveEntityEntry entry2 = service.spawnSilverfish(mockPlayer, mockLocation);
        assertThat(entry2).isNull();

        long warningCountAfterSecond = logRecords.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .count();
        // Exactly one warning logged — never repeated
        assertThat(warningCountAfterSecond).isEqualTo(1);
    }

    @Test
    @DisplayName("Finding 2: Registry entry and cleanup guarantee ensures failure during setup removes ghost immediately")
    void failureDuringSetupCleansUpImmediately() {
        UUID playerId = mockPlayer.getUniqueId();
        AtomicBoolean cleanupInvoked = new AtomicBoolean(false);

        ActiveEntityEntry entry = new ActiveEntityEntry(
                playerId,
                555,
                null,
                mockWorld.getUID(),
                null,
                () -> cleanupInvoked.set(true)
        );

        // Pre-register entry before sending spawn packet
        registry.register(entry);
        assertThat(registry.hasActiveEntities(playerId)).isTrue();
        assertThat(registry.getActiveCount()).isEqualTo(1);

        // Simulate failure during/after send: cleanDespawn called
        registry.cleanDespawn(entry);

        assertThat(cleanupInvoked.get()).isTrue();
        assertThat(registry.hasActiveEntities(playerId)).isFalse();
        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("A glimpse without NMS packets skips safely without a real entity")
    void missingPacketSupportSkipsGlimpse() {
        // Calling spawn in environment without NMS classes returns null safely without throwing
        ActiveEntityEntry entry = service.spawnSilverfish(mockPlayer, mockLocation);
        assertThat(entry).isNull();
        assertThat(worldSpawnCount.get()).isEqualTo(0);
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
