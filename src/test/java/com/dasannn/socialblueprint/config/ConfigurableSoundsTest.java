package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.feature.effects.AmbientEffectDispatcher;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import com.dasannn.socialblueprint.feature.effects.AmbientEntityRegistry;
import com.dasannn.socialblueprint.feature.effects.FakeSilverfishService;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConfigurableSoundsTest {

    @TempDir
    File tempDir;

    private MessageRegistry messageRegistry;
    private ConfigManager configManager;
    private EffectsConfigSection effectsConfig;

    record PlayedSound(Location loc, String sound, SoundCategory category, float volume, float pitch) {}

    private static class MockPlayerState {
        final List<PlayedSound> playedSounds = new ArrayList<>();
        boolean throwOnPlay = false;
        final Player proxy;

        MockPlayerState(World world) {
            UUID uuid = UUID.randomUUID();
            Location loc = new Location(world, 0, 64, 0);

            InvocationHandler handler = (p, method, args) -> {
                String mName = method.getName();
                if ("getUniqueId".equals(mName)) return uuid;
                if ("getLocation".equals(mName)) return loc;
                if ("getWorld".equals(mName)) return world;
                if ("playSound".equals(mName)) {
                    if (throwOnPlay) {
                        throw new RuntimeException("Simulated sound failure");
                    }
                    if (args != null && args.length >= 5) {
                        playedSounds.add(new PlayedSound(
                                (Location) args[0],
                                String.valueOf(args[1]),
                                (SoundCategory) args[2],
                                ((Number) args[3]).floatValue(),
                                ((Number) args[4]).floatValue()
                        ));
                    }
                    return null;
                }
                return defaultValue(method.getReturnType());
            };

            this.proxy = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    handler
            );
        }
    }

    private World createMockWorld(AtomicBoolean worldPlaySoundCalled) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (p, method, args) -> {
                    String mName = method.getName();
                    if ("playSound".equals(mName)) {
                        worldPlaySoundCalled.set(true);
                        return null;
                    }
                    if ("getName".equals(mName)) return "world";
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("ConfigurableSoundsTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);

        File configFile = new File(tempDir, "config.yml");
        copyResource("config.yml", configFile);

        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        effectsConfig = new EffectsConfigSection(
                -10,
                Duration.ofSeconds(30),
                new SingleEffectConfig(Duration.ofSeconds(60), 2, 40, List.of()),
                new SingleEffectConfig(Duration.ofSeconds(30), 3, 0, List.of()),
                new SingleEffectConfig(Duration.ofSeconds(120), 1, 0, List.of()),
                new SingleEffectConfig(Duration.ofSeconds(300), 1, 0, List.of("Ghost"))
        );
    }

    @Test
    @DisplayName("T-135: Sound slots are loaded from YAML, typed into configuration, and exposed on RuntimeSnapshot")
    void t135_loadSlotsFromYamlAndExposeOnSnapshot() {
        String yamlContent = """
            sounds:
              creeper-fuse:
                key: "entity.creeper.primed"
                volume: 0.75
                pitch: 0.6
                category: HOSTILE
              custom-bell:
                key: "block.bell.use"
                volume: 1.0
                pitch: 1.5
                category: RECORDS
            """;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(yamlContent));
        SoundsConfigSection sounds = SoundsConfigSection.load(yaml);

        assertThat(sounds.slots()).hasSize(2);

        SoundSlotConfig creeper = sounds.get("creeper-fuse");
        assertThat(creeper.key()).isEqualTo("entity.creeper.primed");
        assertThat(creeper.volume()).isEqualTo(0.75f);
        assertThat(creeper.pitch()).isEqualTo(0.6f);
        assertThat(creeper.category()).isEqualTo(SoundCategory.HOSTILE);
        assertThat(creeper.isSilent()).isFalse();

        SoundSlotConfig bell = sounds.get("custom-bell");
        assertThat(bell.key()).isEqualTo("block.bell.use");
        assertThat(bell.volume()).isEqualTo(1.0f);
        assertThat(bell.pitch()).isEqualTo(1.5f);
        assertThat(bell.category()).isEqualTo(SoundCategory.RECORDS);

        // Verify exposed on RuntimeSnapshot
        RuntimeSnapshot snapshot = configManager.snapshot();
        assertThat(snapshot.config().sounds()).isNotNull();
        assertThat(snapshot.config().sounds().creeperFuse()).isNotNull();
    }

    @Test
    @DisplayName("T-136: Empty key or absent slot is silent and plays nothing")
    void t136_emptyOrAbsentSlotIsSilent() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        // Case A: Absent slot returns SILENT
        SoundsConfigSection sounds = SoundsConfigSection.defaults();
        SoundSlotConfig absent = sounds.get("non-existent-slot");
        assertThat(absent.isSilent()).isTrue();
        assertThat(absent.key()).isEmpty();

        absent.play(playerState.proxy);
        assertThat(playerState.playedSounds).isEmpty();

        // Case B: Slot with empty key
        SoundSlotConfig emptySlot = new SoundSlotConfig("", 1.0f, 1.0f, SoundCategory.MASTER);
        assertThat(emptySlot.isSilent()).isTrue();

        emptySlot.play(playerState.proxy);
        assertThat(playerState.playedSounds).isEmpty();
        assertThat(worldSoundCalled.get()).isFalse();
    }

    @Test
    @DisplayName("T-136: Unrecognised category logs one warning naming the slot and falls back to MASTER")
    void t136_unrecognisedCategoryLogsWarningOnceAndFallsBackToMaster() {
        List<LogRecord> logRecords = new ArrayList<>();
        Logger testLogger = Logger.getLogger("CategoryTest-" + System.nanoTime());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }
            @Override
            public void flush() {}
            @Override
            public void close() throws SecurityException {}
        });

        String slotName = "slot_with_bad_cat_" + System.nanoTime();

        // First call: logs warning naming the slot and falls back to MASTER
        SoundCategory cat1 = SoundsConfigSection.parseCategory("TOTALLY_UNKNOWN_CAT", slotName, testLogger);
        assertThat(cat1).isEqualTo(SoundCategory.MASTER);
        assertThat(logRecords).hasSize(1);
        assertThat(logRecords.getFirst().getMessage()).contains(slotName);
        assertThat(logRecords.getFirst().getMessage()).contains("TOTALLY_UNKNOWN_CAT");
        assertThat(logRecords.getFirst().getMessage()).contains("MASTER");

        // Second call for the SAME slot: falls back to MASTER without logging a duplicate warning
        SoundCategory cat2 = SoundsConfigSection.parseCategory("TOTALLY_UNKNOWN_CAT", slotName, testLogger);
        assertThat(cat2).isEqualTo(SoundCategory.MASTER);
        assertThat(logRecords).hasSize(1); // Still only 1 warning
    }

    @Test
    @DisplayName("T-136: Sound failure never throws and never blocks the action")
    void t136_soundFailureNeverThrowsOrBlocks() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);
        playerState.throwOnPlay = true; // Force exception on playSound

        SoundSlotConfig slot = new SoundSlotConfig("entity.creeper.primed", 1.0f, 0.5f, SoundCategory.HOSTILE);

        // slot.play() must not throw
        assertThatCode(() -> slot.play(playerState.proxy)).doesNotThrowAnyException();

        // AmbientEffectDispatcher must not throw on play failure
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        assertThatCode(() -> dispatcher.dispatch(
                playerState.proxy,
                AmbientEffectType.CREEPER_SOUND,
                effectsConfig,
                configManager.snapshot()
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-137: Dynamic reload updates next play with no restart, and sound remains private to player")
    void t137_reloadableAndPrivateToAffectedPlayer() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState targetPlayer = new MockPlayerState(mockWorld);

        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        // 1. Initial play with default sounds config
        RuntimeSnapshot snap1 = configManager.snapshot();
        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap1);

        assertThat(targetPlayer.playedSounds).hasSize(1);
        PlayedSound initialSound = targetPlayer.playedSounds.getFirst();
        assertThat(initialSound.sound()).isEqualTo("entity.creeper.primed");
        assertThat(initialSound.volume()).isEqualTo(1.0f);
        assertThat(initialSound.pitch()).isEqualTo(0.5f);

        // World sound was NEVER called (SB-041, T-137: player.playSound, never world.playSound)
        assertThat(worldSoundCalled.get()).isFalse();

        // 2. Dynamic reload: update sounds section with a new sound key and volume
        SoundSlotConfig updatedSlot = new SoundSlotConfig("custom.creeper.warning", 0.4f, 0.8f, SoundCategory.AMBIENT);
        SoundsConfigSection updatedSounds = new SoundsConfigSection(Map.of("creeper-fuse", updatedSlot));
        PluginConfig updatedConfig = snap1.config().withSounds(updatedSounds);
        RuntimeSnapshot snap2 = new RuntimeSnapshot(updatedConfig, snap1.messages());
        configManager.snapshotReference().set(snap2);

        // 3. Next play reads dynamically from snapshot without restart
        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap2);

        assertThat(targetPlayer.playedSounds).hasSize(2);
        PlayedSound reloadedSound = targetPlayer.playedSounds.get(1);
        assertThat(reloadedSound.sound()).isEqualTo("custom.creeper.warning");
        assertThat(reloadedSound.volume()).isEqualTo(0.4f);
        assertThat(reloadedSound.pitch()).isEqualTo(0.8f);
        assertThat(reloadedSound.category()).isEqualTo(SoundCategory.AMBIENT);

        // Still never called world.playSound
        assertThat(worldSoundCalled.get()).isFalse();
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
