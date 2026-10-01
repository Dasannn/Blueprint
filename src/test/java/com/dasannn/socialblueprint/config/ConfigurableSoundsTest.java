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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableSoundsTest {

    @TempDir
    File tempDir;

    private MessageRegistry messageRegistry;
    private ConfigManager configManager;
    private EffectsConfigSection effectsConfig;

    record PlayedSound(Location loc, String sound, SoundCategory category, float volume, float pitch) {}

    private static class MockPlayerState {
        final List<PlayedSound> playedSounds = new ArrayList<>();
        final List<String> stoppedSounds = new ArrayList<>();
        boolean throwOnPlay = false;
        boolean online = true;
        final Player proxy;

        MockPlayerState(World world) {
            UUID uuid = UUID.randomUUID();
            Location loc = new Location(world, 0, 64, 0);

            InvocationHandler handler = (p, method, args) -> {
                String mName = method.getName();
                if ("getUniqueId".equals(mName)) return uuid;
                if ("getLocation".equals(mName)) return loc;
                if ("getWorld".equals(mName)) return world;
                if ("isOnline".equals(mName)) return online;
                if ("stopSound".equals(mName)) {
                    stoppedSounds.add(String.valueOf(args[0]));
                    return null;
                }
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
                Duration.ofSeconds(30),
                new SingleEffectConfig(Duration.ofSeconds(60), 2),
                new SingleEffectConfig(Duration.ofSeconds(30), 3),
                new SingleEffectConfig(Duration.ofSeconds(120), 1),
                new SingleEffectConfig(Duration.ofSeconds(300), 1)
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

    @Test
    @DisplayName("Finding 6: Absent sounds block or absent slot defaults to silent without hardcoded Java sound keys")
    void finding6_absentSoundsBlockOrAbsentSlotIsSilentWithoutJavaDefaults() {
        // Defaults() in Java must have no hardcoded creeper sound (SB-090)
        SoundsConfigSection defaultSection = SoundsConfigSection.defaults();
        assertThat(defaultSection.slots()).isEmpty();
        assertThat(defaultSection.creeperFuse().isSilent()).isTrue();
        assertThat(defaultSection.creeperFuse().key()).isEmpty();

        // Config YAML without a sounds section
        YamlConfiguration emptyYaml = YamlConfiguration.loadConfiguration(new StringReader("version: 1"));
        SoundsConfigSection loadedEmpty = SoundsConfigSection.load(emptyYaml);
        assertThat(loadedEmpty.slots()).isEmpty();
        assertThat(loadedEmpty.creeperFuse().isSilent()).isTrue();
        assertThat(loadedEmpty.get("creeper-fuse").isSilent()).isTrue();
        assertThat(loadedEmpty.get("non-existent-slot").isSilent()).isTrue();
    }

    @Test
    @DisplayName("Finding 7: Validate volume and pitch on load and reject invalid values naming the slot")
    void finding7_validateVolumeAndPitchOnLoad() {
        // Negative volume
        String negativeVolumeYaml = """
            sounds:
              bad-vol-slot:
                key: "entity.creeper.primed"
                volume: -0.1
                pitch: 1.0
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(negativeVolumeYaml))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-vol-slot")
                .hasMessageContaining("volume");

        // Pitch below 0.0
        String lowPitchYaml = """
            sounds:
              bad-pitch-slot:
                key: "entity.creeper.primed"
                volume: 1.0
                pitch: -0.01
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(lowPitchYaml))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-pitch-slot")
                .hasMessageContaining("pitch");

        // Pitch above 2.0
        String highPitchYaml = """
            sounds:
              high-pitch-slot:
                key: "entity.creeper.primed"
                volume: 1.0
                pitch: 2.1
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(highPitchYaml))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("high-pitch-slot")
                .hasMessageContaining("pitch");

        // Direct constructor validation in SoundSlotConfig
        assertThatThrownBy(() -> new SoundSlotConfig("test", -1.0f, 1.0f, SoundCategory.MASTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundSlotConfig("test", Float.NaN, 1.0f, SoundCategory.MASTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundSlotConfig("test", 1.0f, -0.1f, SoundCategory.MASTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundSlotConfig("test", 1.0f, 2.5f, SoundCategory.MASTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundSlotConfig("test", 1.0f, Float.NaN, SoundCategory.MASTER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ambientEpisodeBoundsAllLayersStopsPlaybackAndKeepsQuietAtEveryLevel() {
        World world = createMockWorld(new AtomicBoolean());
        for (com.dasannn.socialblueprint.domain.PsychosisLevel level : List.of(
                com.dasannn.socialblueprint.domain.PsychosisLevel.MEDIUM,
                com.dasannn.socialblueprint.domain.PsychosisLevel.HIGH,
                com.dasannn.socialblueprint.domain.PsychosisLevel.EXTREME)) {
            MockPlayerState player = new MockPlayerState(world);
            TestSoundScheduler scheduler = new TestSoundScheduler();
            List<SoundLayerConfig> played = new ArrayList<>();
            SoundLayerConfig first = new SoundLayerConfig("entity.creeper.primed", 1, 1, SoundCategory.MASTER, 0);
            SoundLayerConfig last = new SoundLayerConfig("block.note_block.pling", 1, 1, SoundCategory.MASTER, 199);
            SoundLayerConfig outside = new SoundLayerConfig("block.note_block.chime", 1, 1, SoundCategory.MASTER, Long.MAX_VALUE);
            SoundsConfigSection sounds = new SoundsConfigSection(Map.of("creeper-fuse", new SoundSlotConfig(List.of(first, last, outside))));
            RuntimeSnapshot snap = new RuntimeSnapshot(configManager.config().withSounds(sounds), configManager.snapshot().messages());
            AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                    new FakeSilverfishService(null, new AmbientEntityRegistry(), null), scheduler,
                    (p, layer) -> played.add(layer));
            dispatcher.dispatch(player.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap);
            long quietTicks = snap.config().effects().quietInterval(level).toMillis() / 50L;
            assertThat(quietTicks).isPositive();
            dispatcher.reserveEpisode(player.proxy.getUniqueId(), 200L + quietTicks);
            assertThat(scheduler.tasks).extracting(TestSoundScheduler.ScheduledTask::delay)
                    .containsExactly(199L, 200L, 200L + quietTicks);
            assertThat(played).containsExactly(first);
            scheduler.tasks.get(0).task().run();
            assertThat(played).containsExactly(first, last);
            scheduler.tasks.get(1).task().run();
            assertThat(player.stoppedSounds).containsExactly(first.key(), last.key());
            assertThat(dispatcher.hasPending(player.proxy.getUniqueId())).isTrue();
            scheduler.tasks.get(2).task().run();
            assertThat(dispatcher.hasPending(player.proxy.getUniqueId())).isFalse();
        }
    }

    @Test
    void interruptedEpisodeCancelsLayersAndStopsSoundImmediately() {
        MockPlayerState player = new MockPlayerState(createMockWorld(new AtomicBoolean()));
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                new FakeSilverfishService(null, new AmbientEntityRegistry(), null), scheduler, (p, layer) -> {});
        RuntimeSnapshot snap = configManager.snapshot();
        dispatcher.dispatch(player.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap);
        dispatcher.cancelPending(player.proxy.getUniqueId());
        assertThat(player.stoppedSounds).contains(snap.config().sounds().get("creeper-fuse").key());
        assertThat(scheduler.tasks).allSatisfy(task -> assertThat(task.cancelled().get()).isTrue());
        assertThat(dispatcher.hasPending(player.proxy.getUniqueId())).isFalse();
    }

    static class TestSoundScheduler implements AmbientEffectDispatcher.SoundScheduler {
        record ScheduledTask(long delay, Runnable task, AtomicBoolean cancelled) implements TaskHandle {
            @Override
            public void cancel() {
                cancelled.set(true);
            }
        }

        final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public TaskHandle schedule(Runnable task, long delayTicks) {
            ScheduledTask st = new ScheduledTask(delayTicks, task, new AtomicBoolean(false));
            tasks.add(st);
            return st;
        }

        void runPending() {
            for (ScheduledTask st : new ArrayList<>(tasks)) {
                if (!st.cancelled.get()) {
                    st.task.run();
                }
            }
            tasks.clear();
        }
    }

    @Test
    @DisplayName("T-138: Single-mapping slot still works, deciding (key, volume, pitch, category, 0) and playing privately")
    void t138_singleMappingSlotStillWorksAndPlaysPrivate() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              creeper-fuse:
                key: "entity.creeper.primed"
                volume: 1.0
                pitch: 0.5
                category: HOSTILE
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.dispatch(playerState.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap);

        // Assert decided sequence of (key, volume, pitch, category, delay)
        assertThat(decided).containsExactly(
                new SoundLayerConfig("entity.creeper.primed", 1.0f, 0.5f, SoundCategory.HOSTILE, 0L)
        );
        assertThat(scheduler.tasks).extracting(TestSoundScheduler.ScheduledTask::delay).containsExactly(200L);
        assertThat(worldSoundCalled.get()).isFalse();
    }

    @Test
    @DisplayName("T-138: Two-layer chord at delay 0 plays simultaneously without delayed tasks")
    void t138_twoLayerChordAtDelayZero() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              chord-slot:
                - key: "block.note_block.pling"
                  volume: 1.0
                  pitch: 1.0
                  delay: 0t
                - key: "block.note_block.chime"
                  volume: 0.8
                  pitch: 1.2
                  delay: 0t
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.playSoundSlot(playerState.proxy, "chord-slot", snap);

        // Both layers play simultaneously at delay 0
        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.pling", 1.0f, 1.0f, SoundCategory.MASTER, 0L),
                new SoundLayerConfig("block.note_block.chime", 0.8f, 1.2f, SoundCategory.MASTER, 0L)
        );
        assertThat(scheduler.tasks).isEmpty();
        assertThat(worldSoundCalled.get()).isFalse();
    }

    @Test
    @DisplayName("T-138: Sequence with delays schedules subsequent layers in order")
    void t138_sequenceWithDelays() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              rating-received:
                - key: "block.note_block.pling"
                  pitch: 1.6
                - key: "entity.experience_orb.pickup"
                  volume: 0.4
                  delay: 4t
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.playSoundSlot(playerState.proxy, "rating-received", snap);

        // Immediately after dispatch, only layer 1 at delay 0 has played
        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.pling", 1.0f, 1.6f, SoundCategory.MASTER, 0L)
        );
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.tasks.get(0).delay).isEqualTo(4L);

        // Run scheduler tasks after 4 ticks
        scheduler.runPending();

        // Second layer has now played in order
        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.pling", 1.0f, 1.6f, SoundCategory.MASTER, 0L),
                new SoundLayerConfig("entity.experience_orb.pickup", 0.4f, 1.0f, SoundCategory.MASTER, 4L)
        );
        assertThat(worldSoundCalled.get()).isFalse();
    }

    @Test
    @DisplayName("T-138: Layer with blank key is skipped while its siblings still play")
    void t138_blankKeySkippedWhileSiblingsPlay() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              sparse-layers:
                - key: ""
                  volume: 1.0
                  pitch: 1.0
                  delay: 0t
                - key: "block.note_block.bell"
                  volume: 0.7
                  pitch: 1.2
                  delay: 2t
                - key: "   "
                  volume: 0.5
                  delay: 3t
                - key: "entity.experience_orb.pickup"
                  volume: 0.4
                  delay: 4t
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.playSoundSlot(playerState.proxy, "sparse-layers", snap);

        // Blank layer at 0 was skipped; 2 non-blank delayed layers were scheduled
        assertThat(decided).isEmpty();
        assertThat(scheduler.tasks).hasSize(2);
        assertThat(scheduler.tasks.get(0).delay).isEqualTo(2L);
        assertThat(scheduler.tasks.get(1).delay).isEqualTo(4L);

        scheduler.runPending();

        // Both non-blank siblings played, blank layers never played
        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.bell", 0.7f, 1.2f, SoundCategory.MASTER, 2L),
                new SoundLayerConfig("entity.experience_orb.pickup", 0.4f, 1.0f, SoundCategory.MASTER, 4L)
        );
        assertThat(worldSoundCalled.get()).isFalse();
    }

    @Test
    @DisplayName("T-138: Logged-out player cancels remaining layers via cancelPending")
    void t138_loggedOutPlayerCancellingRemainingLayers() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              rating-received:
                - key: "block.note_block.pling"
                  pitch: 1.6
                  delay: 0t
                - key: "entity.experience_orb.pickup"
                  volume: 0.4
                  delay: 4t
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.playSoundSlot(playerState.proxy, "rating-received", snap);

        // Immediate layer played
        assertThat(decided).hasSize(1);
        assertThat(scheduler.tasks).hasSize(1);

        // Player logs out and cancelPending is called
        playerState.online = false;
        dispatcher.cancelPending(playerState.proxy.getUniqueId());

        assertThat(scheduler.tasks.get(0).cancelled.get()).isTrue();

        // Scheduler attempts to run pending tasks
        scheduler.runPending();

        // Remaining layer was cancelled and did NOT fire
        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.pling", 1.0f, 1.6f, SoundCategory.MASTER, 0L)
        );
    }

    @Test
    @DisplayName("T-138: Scheduled layer does not fire for player who logged out even without explicit cancelPending")
    void t138_scheduledLayerDoesNotFireForLoggedOutPlayer() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        String yamlContent = """
            sounds:
              rating-received:
                - key: "block.note_block.pling"
                  pitch: 1.6
                  delay: 0t
                - key: "entity.experience_orb.pickup"
                  volume: 0.4
                  delay: 4t
            """;
        SoundsConfigSection sounds = SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(yamlContent)));
        RuntimeSnapshot snap = new RuntimeSnapshot(configManager.snapshot().config().withSounds(sounds), configManager.snapshot().messages());

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        dispatcher.playSoundSlot(playerState.proxy, "rating-received", snap);
        assertThat(decided).hasSize(1);

        // Player goes offline without cancelPending being called beforehand
        playerState.online = false;

        // When scheduled task executes, the isOnline() guard prevents playback
        scheduler.runPending();

        assertThat(decided).containsExactly(
                new SoundLayerConfig("block.note_block.pling", 1.0f, 1.6f, SoundCategory.MASTER, 0L)
        );
    }

    @Test
    @DisplayName("T-138: Unrecognised category in layer logs one warning naming slot and falls back to MASTER")
    void t138_unrecognisedCategoryInLayerLogsWarningOnce() {
        List<LogRecord> logRecords = new ArrayList<>();
        Logger testLogger = Logger.getLogger("LayerCategoryTest-" + System.nanoTime());
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

        String yamlContent = """
            sounds:
              bad-cat-slot:
                - key: "block.note_block.pling"
                  category: "NOT_A_REAL_CATEGORY"
                - key: "block.note_block.chime"
                  category: "ANOTHER_BOGUS_CATEGORY"
            """;

        SoundsConfigSection sounds = SoundsConfigSection.load(
                YamlConfiguration.loadConfiguration(new StringReader(yamlContent)),
                testLogger
        );

        SoundSlotConfig slot = sounds.get("bad-cat-slot");
        assertThat(slot.layers()).hasSize(2);
        assertThat(slot.layers().get(0).category()).isEqualTo(SoundCategory.MASTER);
        assertThat(slot.layers().get(1).category()).isEqualTo(SoundCategory.MASTER);

        // Exactly one warning logged naming the slot
        assertThat(logRecords).hasSize(1);
        assertThat(logRecords.get(0).getMessage()).contains("bad-cat-slot");
        assertThat(logRecords.get(0).getMessage()).contains("MASTER");
    }

    @Test
    @DisplayName("T-138: Invalid volume, pitch, or delay in layers rejected on load naming offending slot")
    void t138_invalidValuesInLayerRejectedOnLoad() {
        // Negative volume in layer
        String negVol = """
            sounds:
              bad-layer-vol:
                - key: "entity.creeper.primed"
                  volume: -0.5
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(negVol))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-layer-vol")
                .hasMessageContaining("volume");

        // Pitch > 2.0 in layer
        String highPitch = """
            sounds:
              bad-layer-pitch:
                - key: "entity.creeper.primed"
                  pitch: 2.1
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(highPitch))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-layer-pitch")
                .hasMessageContaining("pitch");

        // Pitch < 0.0 in layer
        String lowPitch = """
            sounds:
              bad-layer-pitch-low:
                - key: "entity.creeper.primed"
                  pitch: -0.1
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(lowPitch))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-layer-pitch-low")
                .hasMessageContaining("pitch");

        // Negative delay in layer
        String negDelay = """
            sounds:
              bad-layer-delay:
                - key: "entity.creeper.primed"
                  delay: -4t
            """;
        assertThatThrownBy(() -> SoundsConfigSection.load(YamlConfiguration.loadConfiguration(new StringReader(negDelay))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bad-layer-delay")
                .hasMessageContaining("delay");

        // Direct constructor validation in SoundLayerConfig
        assertThatThrownBy(() -> new SoundLayerConfig("test", -1.0f, 1.0f, SoundCategory.MASTER, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundLayerConfig("test", 1.0f, 2.5f, SoundCategory.MASTER, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundLayerConfig("test", 1.0f, -0.1f, SoundCategory.MASTER, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SoundLayerConfig("test", 1.0f, 1.0f, SoundCategory.MASTER, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T-138: Dynamic reload updates layers on next play without restart (SB-091)")
    void t138_dynamicReloadUpdatesLayersOnNextPlay() {
        AtomicBoolean worldSoundCalled = new AtomicBoolean(false);
        World mockWorld = createMockWorld(worldSoundCalled);
        MockPlayerState playerState = new MockPlayerState(mockWorld);

        List<SoundLayerConfig> decided = new ArrayList<>();
        TestSoundScheduler scheduler = new TestSoundScheduler();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService, scheduler,
                (p, layer) -> decided.add(layer)
        );

        // 1. Initial play with default single-mapping slot
        RuntimeSnapshot snap1 = configManager.snapshot();
        dispatcher.dispatch(playerState.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap1);

        assertThat(decided).hasSize(1);
        assertThat(decided.get(0).key()).isEqualTo("entity.creeper.primed");

        // 2. Dynamic reload with multi-layer chord
        SoundSlotConfig updatedSlot = new SoundSlotConfig(List.of(
                new SoundLayerConfig("custom.horn.a", 0.6f, 1.0f, SoundCategory.AMBIENT, 0L),
                new SoundLayerConfig("custom.horn.b", 0.6f, 1.5f, SoundCategory.AMBIENT, 0L)
        ));
        SoundsConfigSection updatedSounds = new SoundsConfigSection(Map.of("creeper-fuse", updatedSlot));
        PluginConfig updatedConfig = snap1.config().withSounds(updatedSounds);
        RuntimeSnapshot snap2 = new RuntimeSnapshot(updatedConfig, snap1.messages());
        configManager.snapshotReference().set(snap2);

        // 3. Next play uses the updated layers immediately
        dispatcher.dispatch(playerState.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snap2);

        assertThat(decided).hasSize(3);
        assertThat(decided.get(1)).isEqualTo(new SoundLayerConfig("custom.horn.a", 0.6f, 1.0f, SoundCategory.AMBIENT, 0L));
        assertThat(decided.get(2)).isEqualTo(new SoundLayerConfig("custom.horn.b", 0.6f, 1.5f, SoundCategory.AMBIENT, 0L));
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
