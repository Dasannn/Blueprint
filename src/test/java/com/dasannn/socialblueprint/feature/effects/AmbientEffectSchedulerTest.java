package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SingleEffectConfig;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import com.dasannn.socialblueprint.domain.HonorKind;

class AmbientEffectSchedulerTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private ProfileService profileService;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private AmbientEffectScheduler scheduler;
    private final List<DispatchedRecord> dispatchedList = new ArrayList<>();
    private final List<Player> onlinePlayers = new ArrayList<>();

    private record DispatchedRecord(Player player, AmbientEffectType type, long timestamp) {}

    @Test void serenityUsesItsOwnMagnitudeAndSubjectBudgetWithoutMadnessDispatch() {
        UUID uuid = UUID.randomUUID();
        Player player = createMockPlayer(uuid, "Serene");
        onlinePlayers.add(player);
        PlayerId id = PlayerId.of(uuid);
        profileService.mind().applyAsync(id, com.dasannn.socialblueprint.domain.MindInput.CLEAN_DAY,
                new com.dasannn.socialblueprint.domain.MindInputConfig(true, 50, 50, 1), "test", Instant.now()).join();
        profileService.loadViewAsync(id, "Serene", configManager.snapshot()).join();
        List<String> serene = new ArrayList<>();
        var registry = new AmbientEntityRegistry();
        var dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                new FakeSilverfishService(null, registry, null)) {
            @Override public boolean dispatchSerene(Player subject, String effect, RuntimeSnapshot snapshot,
                    java.util.function.BooleanSupplier eligible) {
                assertThat(subject).isSameAs(player);
                assertThat(eligible.getAsBoolean()).isTrue();
                serene.add(effect);
                return true;
            }
            @Override public boolean dispatch(Player subject, AmbientEffectType effect, EffectsConfigSection config, RuntimeSnapshot snapshot) {
                throw new AssertionError("Serenity must never deliver madness");
            }
        };
        var scheduler = new AmbientEffectScheduler(null, configManager, profileService, dispatcher, () -> onlinePlayers);
        scheduler.tickAt(1_000_000);
        assertThat(serene).hasSize(1);
        scheduler.tickAt(1_000_001);
        assertThat(serene).hasSize(1);
        // Kill invalidation immediately makes the quick view neutral until its storage rebuild.
        psychosisRepo.saveAsync(new PsychosisEvent(id, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, Instant.now())).join();
        scheduler.tickAt(1_000_002);
        assertThat(serene).hasSize(1);
        assertThat(scheduler.getState(uuid).canStartEpisode(1_000_002)).isFalse();
    }

    @BeforeEach
    void setUp() throws Exception {
        dispatchedList.clear();
        onlinePlayers.clear();

        File configFile = new File(tempDir, "config.yml");
        copyResource("config.yml", configFile);
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("AmbientEffectSchedulerTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);

        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        // Custom effects config with known cooldowns and caps
        SingleEffectConfig silverfish = new SingleEffectConfig(Duration.ofSeconds(60), 2);
        SingleEffectConfig whisper = new SingleEffectConfig(Duration.ofSeconds(30), 3);
        SingleEffectConfig creeper = new SingleEffectConfig(Duration.ofSeconds(120), 1);
        SingleEffectConfig fakeAnnounce = new SingleEffectConfig(Duration.ofSeconds(300), 1);

        EffectsConfigSection effectsCfg = new EffectsConfigSection(
                Duration.ofSeconds(30),
                silverfish,
                whisper,
                creeper,
                fakeAnnounce,
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(30), 200, singleConcurrency()
        );

        PluginConfig fullConfig = configManager.config().withEffects(effectsCfg);
        configManager.snapshotReference().set(new RuntimeSnapshot(fullConfig, configManager.snapshot().messages()));

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        ProfileRepository profileRepo = new ProfileRepository(storage);
        PlayerLookup testLookup = nameOrUuid -> Optional.empty();

        profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                testLookup,
                logger
        );

        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, logger);

        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        ) {
            @Override
            public boolean dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
                dispatchedList.add(new DispatchedRecord(player, type, System.currentTimeMillis()));
                return true;
            }
        };

        scheduler = new AmbientEffectScheduler(
                null,
                configManager,
                profileService,
                dispatcher,
                () -> onlinePlayers
        );
    }

    private static com.dasannn.socialblueprint.config.PresentationConfig singleConcurrency() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        for (String level : List.of("low", "medium", "high", "extreme"))
            yaml.set("effects.episodes." + level + ".max-concurrent", 1);
        return com.dasannn.socialblueprint.config.PresentationConfig.load(yaml);
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private Player createMockPlayer(UUID uuid, String name) {
        Thread mainThread = Thread.currentThread();
        InvocationHandler handler = (proxy, method, args) -> {
            assertThat(Thread.currentThread()).as("Player API must stay on the main thread").isSameAs(mainThread);
            String m = method.getName();
            if ("equals".equals(m) && method.getParameterCount() == 1) return proxy == args[0];
            if ("hashCode".equals(m) && method.getParameterCount() == 0) return System.identityHashCode(proxy);
            if ("toString".equals(m) && method.getParameterCount() == 0) return name;
            if ("getUniqueId".equals(m)) return uuid;
            if ("getName".equals(m)) return name;
            if ("isOnline".equals(m)) return true;
            return defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );
    }

    private void setPlayerStatus(UUID uuid, int statusScore) {
        PlayerId id = PlayerId.of(uuid);
        reputationRepo.saveAsync(new ReputationEvent(
                PlayerId.of(UUID.randomUUID()),
                id,
                statusScore,
                statusScore < 0 ? HonorKind.ADMIN_TAKE : HonorKind.ADMIN_GIVE,
                0.0,
                "Reputation set",
                Instant.now()
        )).join();
        // Warm up / load view in profile service
        profileService.loadViewAsync(id, "Player-" + uuid.toString().substring(0, 4), configManager.snapshot().config().tiers().ladder()).join();
    }

    private void setPsychosis(UUID uuid, int kills) {
        PlayerId id = PlayerId.of(uuid);
        for (int i = 0; i < kills; i++) {
            psychosisRepo.saveAsync(new PsychosisEvent(id, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, Instant.now())).join();
        }
        profileService.loadViewAsync(id, "TestPlayer", configManager.snapshot()).join();
    }

    @Test
    void catalogueFloorsGateActualSchedulerSelectionWithoutRenderingBukkitEffects() {
        RuntimeSnapshot original = configManager.snapshot();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        long now = 1_000_000L;
        for (AmbientEffectType chosen : List.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES,
                AmbientEffectType.SCREEN_FLASH, AmbientEffectType.SOURCE_LESS_SOUNDS,
                AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN, AmbientEffectType.HURT_FLASH)) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            for (AmbientEffectType type : List.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES,
                    AmbientEffectType.SCREEN_FLASH, AmbientEffectType.SOURCE_LESS_SOUNDS,
                    AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN, AmbientEffectType.HURT_FLASH, AmbientEffectType.VICTIM_GHOST))
                yaml.set("effects." + type.configId() + ".enabled", type == chosen);
            com.dasannn.socialblueprint.config.PresentationConfig presentation =
                    com.dasannn.socialblueprint.config.PresentationConfig.load(yaml);
            EffectsConfigSection config = new EffectsConfigSection(Duration.ofMillis(1), disabled, disabled, disabled, disabled,
                    Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 100, presentation);
            configManager.snapshotReference().set(new RuntimeSnapshot(original.config().withEffects(config), original.messages()));
            for (int kills : List.of(0, 1, 2, 5, 10)) {
                onlinePlayers.clear();
                dispatchedList.clear();
                UUID uuid = UUID.randomUUID();
                onlinePlayers.add(createMockPlayer(uuid, "Catalogue"));
                setPsychosis(uuid, kills);
                scheduler.tickAt(now);
                boolean eligible = kills >= (chosen.floor() == PsychosisLevel.HIGH ? 5 : chosen.floor() == PsychosisLevel.LOW ? 1 : 2);
                if (eligible) assertThat(dispatchedList).singleElement()
                        .satisfies(record -> assertThat(record.type()).isEqualTo(chosen));
                else assertThat(dispatchedList).isEmpty();
                now += 1_000_000L;
            }
        }
    }

    @Test void schedulerPassesScaledVisualsAndStartsQuietAfterTheirScaledEnd() {
        RuntimeSnapshot original = configManager.snapshot();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        long now = 10_000_000L;
        for (AmbientEffectType chosen : List.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES,
                AmbientEffectType.SCREEN_FLASH, AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN,
                AmbientEffectType.BOSS_BAR, AmbientEffectType.SILVERFISH)) {
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
            if (chosen != AmbientEffectType.SILVERFISH) yaml.set("effects." + chosen.configId() + ".enabled", true);
            yaml.set("effects.episodes.medium.interval-ticks", 2400);
            yaml.set("effects.episodes.high.interval-ticks", 1200);
            yaml.set("effects.episodes.extreme.interval-ticks", 400);
            var presentation = com.dasannn.socialblueprint.config.PresentationConfig.load(yaml);
            var config = new EffectsConfigSection(Duration.ofMillis(1),
                    chosen == AmbientEffectType.SILVERFISH ? SingleEffectConfig.of(Duration.ZERO, 6) : disabled,
                    disabled, disabled, disabled, Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofSeconds(20), 100, presentation);
            var snapshot = new RuntimeSnapshot(original.config().withEffects(config), original.messages());
            configManager.snapshotReference().set(snapshot);
            List<Long> renderedDurations = new ArrayList<>();
            var dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                    new FakeSilverfishService(null, new AmbientEntityRegistry(), null)) {
                @Override public boolean dispatch(Player viewer, AmbientEffectType type, EffectsConfigSection settings, RuntimeSnapshot captured) {
                    assertThat(type).isEqualTo(chosen);
                    assertThat(captured).isSameAs(snapshot);
                    renderedDurations.add(settings.presentation().durationTicks(type, captured.config().sounds()));
                    return true;
                }
            };
            var local = new AmbientEffectScheduler(null, configManager, profileService, dispatcher, () -> onlinePlayers);
            for (int kills : List.of(2, 5, 10)) {
                if (kills == 2 && chosen.floor() == PsychosisLevel.HIGH) continue;
                UUID id = UUID.randomUUID();
                onlinePlayers.clear();
                onlinePlayers.add(createMockPlayer(id, "Scaled"));
                setPsychosis(id, kills);
                PsychosisLevel level = kills == 2 ? PsychosisLevel.MEDIUM : kills == 5 ? PsychosisLevel.HIGH : PsychosisLevel.EXTREME;
                long base = chosen == AmbientEffectType.PARTICLES ? presentation.particles().durationTicks()
                        : presentation.durationTicks(chosen, snapshot.config().sounds());
                long expected = Math.min(chosen == AmbientEffectType.SKY ? 200 : 100, (long) Math.ceil(base * (kills == 2 ? 1 : kills == 5 ? 1.5 : 2)));
                if (chosen == AmbientEffectType.PARTICLES) expected = Math.max(expected, 45);
                local.tickAt(now);
                assertThat(renderedDurations.getLast()).isEqualTo(expected);
                long allowedAt = now + expected * 50 + config.quietInterval(level).toMillis();
                assertThat(local.getState(id).canStartEpisode(allowedAt - 1)).isFalse();
                assertThat(local.getState(id).canStartEpisode(allowedAt)).isTrue();
                now += 1_000_000;
            }
        }
    }

    @Test
    void actualSchedulerIncludesFinalSourceLessLayerAndPlaybackBeforeQuiet() {
        RuntimeSnapshot original = configManager.snapshot();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("effects.source-less-sounds.enabled", true);
        yaml.set("effects.source-less-sounds.cooldown-ticks", 1);
        com.dasannn.socialblueprint.config.PresentationConfig presentation =
                com.dasannn.socialblueprint.config.PresentationConfig.load(yaml);
        EffectsConfigSection config = new EffectsConfigSection(Duration.ofMillis(1), disabled, disabled, disabled, disabled,
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 100, presentation);
        Map<String, com.dasannn.socialblueprint.config.SoundSlotConfig> slots = new java.util.HashMap<>(original.config().sounds().slots());
        slots.put("source-less", new com.dasannn.socialblueprint.config.SoundSlotConfig(List.of(
                new com.dasannn.socialblueprint.config.SoundLayerConfig("minecraft:ambient.cave", 1, 1, org.bukkit.SoundCategory.AMBIENT, 80))));
        configManager.snapshotReference().set(new RuntimeSnapshot(original.config().withEffects(config)
                .withSounds(new com.dasannn.socialblueprint.config.SoundsConfigSection(slots)), original.messages()));
        UUID uuid = UUID.randomUUID();
        onlinePlayers.add(createMockPlayer(uuid, "SoundTail"));
        setPsychosis(uuid, 10);
        long now = 1_000_000L;
        scheduler.tickAt(now);
        assertThat(dispatchedList).hasSize(1);
        scheduler.tickAt(now + 4000L);
        assertThat(dispatchedList).hasSize(1);
        scheduler.tickAt(now + 5000L);
        assertThat(dispatchedList).hasSize(1);
        scheduler.tickAt(now + 6000L);
        assertThat(dispatchedList).hasSize(2);
    }

    @Test
    void coldCacheWaitsForPlainPsychosisViewAndNeverTouchesPlayerOnStorageThread() {
        UUID uuid = UUID.randomUUID();
        PlayerId id = PlayerId.of(uuid);
        onlinePlayers.add(createMockPlayer(uuid, "ColdCache"));
        for (int i = 0; i < 5; i++) {
            psychosisRepo.saveAsync(new PsychosisEvent(id, PlayerId.of(UUID.randomUUID()), CombatContext.OPEN, Instant.now())).join();
        }
        scheduler.tickAt(1_000_000L);
        assertThat(dispatchedList).isEmpty();
        profileService.loadViewAsync(id, "ColdCache", configManager.snapshot()).join();
        scheduler.tickAt(1_030_000L);
        assertThat(dispatchedList).hasSize(1);
    }

    @Test
    void highPsychosisCanReceivePhantomWithOtherEffectsDisabled() {
        UUID uuid = UUID.randomUUID();
        onlinePlayers.add(createMockPlayer(uuid, "High"));
        EffectsConfigSection current = configManager.config().effects();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        EffectsConfigSection onlyPhantom = new EffectsConfigSection(current.checkInterval(), current.silverfish(), disabled, disabled, disabled);
        RuntimeSnapshot before = configManager.snapshot();
        configManager.snapshotReference().set(new RuntimeSnapshot(before.config().withEffects(onlyPhantom), before.messages()));
        setPsychosis(uuid, 5);
        scheduler.tickAt(1_000_000L);
        assertThat(dispatchedList).singleElement().satisfies(record -> assertThat(record.type()).isEqualTo(AmbientEffectType.SILVERFISH));
    }

    @Test
    void peacefulPhantomSkipKeepsCapCooldownAndQuietReservationUntouched() {
        UUID id = UUID.randomUUID();
        Player base = createMockPlayer(id, "Peaceful");
        org.bukkit.World world = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDifficulty")) return org.bukkit.Difficulty.PEACEFUL;
                    throw new AssertionError("Unexpected world access: " + method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> method.getName().equals("getWorld") ? world : method.invoke(base, args));
        EffectsConfigSection current = configManager.config().effects();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        EffectsConfigSection onlyPhantom = new EffectsConfigSection(current.checkInterval(), current.silverfish(), disabled, disabled, disabled);
        RuntimeSnapshot before = configManager.snapshot();
        configManager.snapshotReference().set(new RuntimeSnapshot(before.config().withEffects(onlyPhantom), before.messages()));
        setPsychosis(id, 5);
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        var dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                new FakeSilverfishService(null, registry, null));
        var local = new AmbientEffectScheduler(null, configManager, profileService, dispatcher, () -> List.of(player));
        for (long now : List.of(1_000_000L, 1_002_000L)) {
            local.tickAt(now);
            PlayerEffectState state = local.getState(id);
            assertThat(state.getSessionCount(AmbientEffectType.SILVERFISH)).isZero();
            assertThat(state.getLastFiredMillis(AmbientEffectType.SILVERFISH)).isZero();
            assertThat(state.canStartEpisode(now)).isTrue();
            assertThat(state.canFire(AmbientEffectType.SILVERFISH, onlyPhantom.silverfish(), now)).isTrue();
            assertThat(registry.getActiveCount()).isZero();
        }
    }

    @Test
    void lowStatusWithoutPsychosisAndHighStatusWithPsychosisAreIndependent() {
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        Player lowPlayer = createMockPlayer(low, "LowStatus");
        Player highPlayer = createMockPlayer(high, "HighStatus");
        onlinePlayers.addAll(List.of(lowPlayer, highPlayer));
        setPlayerStatus(low, -50);
        setPlayerStatus(high, 50);
        setPsychosis(high, 5);
        scheduler.tickAt(1_000_000L);
        assertThat(dispatchedList).extracting(DispatchedRecord::player).containsExactly(highPlayer);
    }

    @Test
    void mediumNeverReceivesPhantomAndCannotChainDifferentEffectsDuringQuiet() {
        UUID uuid = UUID.randomUUID();
        onlinePlayers.add(createMockPlayer(uuid, "Medium"));
        setPsychosis(uuid, 2);
        scheduler.tickAt(1_000_000L);
        assertThat(dispatchedList).hasSize(1);
        assertThat(dispatchedList.getFirst().type()).isNotEqualTo(AmbientEffectType.SILVERFISH);
        scheduler.tickAt(1_030_000L);
        assertThat(dispatchedList).hasSize(1);
        scheduler.tickAt(1_330_000L);
        assertThat(dispatchedList).hasSize(2);
        assertThat(dispatchedList).allSatisfy(record -> assertThat(record.type()).isNotEqualTo(AmbientEffectType.SILVERFISH));
    }

    @Test
    void schedulerCadenceActuallyDiffersForMediumHighAndExtreme() {
        UUID medium = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        UUID extreme = UUID.randomUUID();
        Player mediumPlayer = createMockPlayer(medium, "Medium");
        Player highPlayer = createMockPlayer(high, "High");
        Player extremePlayer = createMockPlayer(extreme, "Extreme");
        onlinePlayers.addAll(List.of(mediumPlayer, highPlayer, extremePlayer));
        setPsychosis(medium, 2);
        setPsychosis(high, 5);
        setPsychosis(extreme, 10);
        scheduler.tickAt(1_000_000L);
        assertThat(dispatchedList).hasSize(3);
        scheduler.tickAt(1_060_000L);
        assertThat(dispatchedList).filteredOn(record -> record.player() == mediumPlayer).hasSize(1);
        assertThat(dispatchedList).filteredOn(record -> record.player() == highPlayer).hasSize(1);
        assertThat(dispatchedList).filteredOn(record -> record.player() == extremePlayer).hasSize(2);
        scheduler.tickAt(1_140_000L);
        assertThat(dispatchedList).filteredOn(record -> record.player() == mediumPlayer).hasSize(1);
        assertThat(dispatchedList).filteredOn(record -> record.player() == highPlayer).hasSize(2);
    }

    @Test
    void cadenceDecreasesByLevelAndEveryEpisodeKeepsSilence() {
        EffectsConfigSection cfg = configManager.snapshot().config().effects();
        assertThat(cfg.quietInterval(PsychosisLevel.MEDIUM)).isGreaterThan(cfg.quietInterval(PsychosisLevel.HIGH));
        assertThat(cfg.quietInterval(PsychosisLevel.HIGH)).isGreaterThan(cfg.quietInterval(PsychosisLevel.EXTREME));
        for (PsychosisLevel level : List.of(PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME)) {
            PlayerEffectState state = new PlayerEffectState();
            long end = 1_000_000L + cfg.maxEpisodeTicks() * 50L;
            long quiet = cfg.quietInterval(level).toMillis();
            assertThat(quiet).isPositive();
            state.recordEpisode(1_000_000L, cfg.maxEpisodeTicks() * 50L + quiet);
            assertThat(state.canStartEpisode(end)).isFalse();
            assertThat(state.canStartEpisode(end + quiet - 1)).isFalse();
            assertThat(state.canStartEpisode(end + quiet)).isTrue();
        }
    }

    @Test
    @DisplayName("SB-139: Neutral triggers nothing at any status")
    void neutralNeverFiresRegardlessOfStatus() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        onlinePlayers.add(createMockPlayer(p1, "AtThreshold"));
        onlinePlayers.add(createMockPlayer(p2, "AboveThreshold"));
        onlinePlayers.add(createMockPlayer(p3, "PositiveStatus"));

        setPlayerStatus(p1, -50); // Lowest Psychosis, deeply negative status
        setPlayerStatus(p2, -5);  // Lowest Psychosis, negative status
        setPlayerStatus(p3, 20);  // Lowest Psychosis, positive status
        setPsychosis(p3, 0); // Neutral remains silent.

        scheduler.tickAt(100_000L);

        assertThat(dispatchedList).isEmpty();
    }

    @Test
    @DisplayName("T-070 / SB-040: High status with Psychosis receives an ambient effect")
    void highStatusWithPsychosisFiresEffect() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "LowStatusPlayer");
        onlinePlayers.add(player);

        setPlayerStatus(p, 50);
        setPsychosis(p, 5);

        scheduler.tickAt(100_000L);

        assertThat(dispatchedList).hasSize(1);
        assertThat(dispatchedList.get(0).player()).isEqualTo(player);
        assertThat(dispatchedList.get(0).type()).isNotNull();
    }

    @Test
    @DisplayName("T-070 / SB-043: Independent cooldowns per effect are strictly enforced")
    void independentCooldownsEnforced() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "CooldownPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(p, -15);
        setPsychosis(p, 5);

        PlayerEffectState state = scheduler.getOrCreateState(p);
        long t0 = 1_000_000L;

        // Fire WHISPER (cooldown 30s)
        state.recordFired(AmbientEffectType.WHISPER, t0);

        EffectsConfigSection cfg = configManager.snapshot().config().effects();

        // 10 seconds later, WHISPER cannot fire, but SILVERFISH can
        long t1 = t0 + 10_000L;
        assertThat(state.canFire(AmbientEffectType.WHISPER, cfg.whisper(), t1)).isFalse();
        assertThat(state.canFire(AmbientEffectType.SILVERFISH, cfg.silverfish(), t1)).isTrue();
        assertThat(state.canFire(AmbientEffectType.CREEPER_SOUND, cfg.creeper(), t1)).isTrue();
        assertThat(state.canFire(AmbientEffectType.FAKE_ANNOUNCEMENT, cfg.fakeAnnouncement(), t1)).isTrue();

        // 31 seconds later, WHISPER cooldown has expired
        long t2 = t0 + 31_000L;
        assertThat(state.canFire(AmbientEffectType.WHISPER, cfg.whisper(), t2)).isTrue();
    }

    @Test
    @DisplayName("T-070 / SB-043: Independent per-session caps per effect are strictly enforced")
    void independentSessionCapsEnforced() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "CapPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(p, -20);
        setPsychosis(p, 5);

        PlayerEffectState state = scheduler.getOrCreateState(p);
        EffectsConfigSection cfg = configManager.snapshot().config().effects();

        // CREEPER_SOUND has cap of 1
        long t = 1_000_000L;
        assertThat(state.canFire(AmbientEffectType.CREEPER_SOUND, cfg.creeper(), t)).isTrue();

        state.recordFired(AmbientEffectType.CREEPER_SOUND, t);

        // After 1 firing, even after cooldown expires (120s later), cap is reached
        long tFuture = t + 200_000L;
        assertThat(state.canFire(AmbientEffectType.CREEPER_SOUND, cfg.creeper(), tFuture)).isFalse();

        // But SILVERFISH (cap 2) and WHISPER (cap 3) can still fire
        assertThat(state.canFire(AmbientEffectType.SILVERFISH, cfg.silverfish(), tFuture)).isTrue();
        assertThat(state.canFire(AmbientEffectType.WHISPER, cfg.whisper(), tFuture)).isTrue();
    }

    @Test
    @DisplayName("T-070 / SB-043: Player quit resets session counts so caps refresh for next session")
    void playerQuitResetsSessionCaps() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "QuittingPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(p, -20);
        setPsychosis(p, 5);

        PlayerEffectState state = scheduler.getOrCreateState(p);
        EffectsConfigSection cfg = configManager.snapshot().config().effects();

        // Exhaust CREEPER_SOUND cap (cap = 1)
        state.recordFired(AmbientEffectType.CREEPER_SOUND, 100_000L);
        assertThat(state.canFire(AmbientEffectType.CREEPER_SOUND, cfg.creeper(), 500_000L)).isFalse();

        // Player quits
        scheduler.handlePlayerQuit(p);

        // Reconnect in new session -> session state is refreshed
        PlayerEffectState newState = scheduler.getOrCreateState(p);
        assertThat(newState.canFire(AmbientEffectType.CREEPER_SOUND, cfg.creeper(), 500_000L)).isTrue();
    }

    @Test
    @DisplayName("Finding 6: effects.check-interval changes dynamically at runtime without server restart")
    void checkIntervalChangesDynamicallyAtRuntime() {
        UUID uuid = UUID.randomUUID();
        Player player = createMockPlayer(uuid, "IntervalPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(uuid, -20);
        setPsychosis(uuid, 5);

        // Initial config interval is 30s
        long t0 = 100_000L;
        scheduler.tickAt(t0);
        assertThat(dispatchedList).hasSize(1);

        // 10 seconds later: should NOT tick because 10s < 30s
        long t1 = t0 + 10_000L;
        scheduler.tickAt(t1);
        assertThat(dispatchedList).hasSize(1);

        // Dynamically update config to 5s interval (without restarting scheduler)
        EffectsConfigSection current = configManager.snapshot().config().effects();
        EffectsConfigSection updated = new EffectsConfigSection(
                Duration.ofSeconds(5),
                current.silverfish(),
                current.whisper(),
                current.creeper(),
                current.fakeAnnouncement(),
                Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofSeconds(20), 200, singleConcurrency()
        );
        configManager.snapshotReference().set(new RuntimeSnapshot(
                configManager.config().withEffects(updated),
                configManager.snapshot().messages()
        ));

        // A cached view belongs to the snapshot it was computed under (T-103
        // finding 4), so publishing a new snapshot needs a fresh load first.
        profileService.loadViewAsync(PlayerId.of(uuid), "IntervalPlayer", configManager.snapshot()).join();

        // After the High quiet period, the shorter check interval allows another episode.
        long t2 = t0 + 135_000L;
        scheduler.tickAt(t2);
        assertThat(dispatchedList).hasSize(2);
    }

    @Test
    @DisplayName("Finding 7: Failed dispatch does not burn session allowance or record cooldown")
    void failedDispatchDoesNotConsumeAllowanceOrCooldown() {
        UUID uuid = UUID.randomUUID();
        Player player = createMockPlayer(uuid, "FailingDispatchPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(uuid, -20);
        setPsychosis(uuid, 5);

        // Replace scheduler with one that has a failing dispatcher
        java.util.concurrent.atomic.AtomicBoolean dispatchSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
        AmbientEffectDispatcher failingDispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager,
                new FakeSilverfishService(null, new AmbientEntityRegistry(), null)
        ) {
            @Override
            public boolean dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
                return dispatchSuccess.get();
            }
        };

        AmbientEffectScheduler failScheduler = new AmbientEffectScheduler(
                null, configManager, profileService, failingDispatcher, () -> List.of(player)
        );

        // Past the longest configured cooldown (fake-announcement, 15m), so a fresh
        // state can fire every effect type.
        long t0 = 1_000_000L;
        // Dispatch fails
        failScheduler.tickAt(t0);

        PlayerEffectState state = failScheduler.getOrCreateState(uuid);
        EffectsConfigSection cfg = configManager.snapshot().config().effects();

        // Verify session counts are all 0 and can still fire
        for (AmbientEffectType type : AmbientEffectType.values()) {
            assertThat(state.getSessionCount(type)).isEqualTo(0);
            assertThat(state.canFire(type, cfg.getEffect(type), t0)).isTrue();
        }

        // Now enable dispatch success
        dispatchSuccess.set(true);
        failScheduler.tickAt(t0 + 40_000L);

        // Exactly one effect fired and recorded
        int totalFired = 0;
        for (AmbientEffectType type : AmbientEffectType.values()) {
            totalFired += state.getSessionCount(type);
        }
        assertThat(totalFired).isEqualTo(1);
    }

    @Test void madnessStartsDistinctEffectsTogetherAndQuietFollowsTheLastTail() {
        RuntimeSnapshot original = configManager.snapshot();
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        for (AmbientEffectType type : List.of(AmbientEffectType.WHISPER, AmbientEffectType.BOSS_BAR,
                AmbientEffectType.SCREEN_FLASH, AmbientEffectType.SOURCE_LESS_SOUNDS)) {
            yaml.set("effects." + type.configId() + ".enabled", true);
            yaml.set("effects." + type.configId() + ".cooldown-ticks", 1);
        }
        yaml.set("effects.episodes.low.interval-ticks", 4800);
        Map<String, com.dasannn.socialblueprint.config.SoundSlotConfig> slots = new java.util.HashMap<>(original.config().sounds().slots());
        slots.put("source-less", new com.dasannn.socialblueprint.config.SoundSlotConfig(List.of(
                new com.dasannn.socialblueprint.config.SoundLayerConfig("minecraft:ambient.cave", 1, 1, org.bukkit.SoundCategory.AMBIENT, 80))));
        var config = new EffectsConfigSection(Duration.ofMillis(1), disabled, disabled, disabled, disabled,
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 200,
                com.dasannn.socialblueprint.config.PresentationConfig.load(yaml));
        configManager.snapshotReference().set(new RuntimeSnapshot(original.config().withEffects(config)
                .withSounds(new com.dasannn.socialblueprint.config.SoundsConfigSection(slots)), original.messages()));
        for (int kills : List.of(1, 2, 5, 10)) {
            onlinePlayers.clear(); dispatchedList.clear();
            UUID uuid = UUID.randomUUID(); onlinePlayers.add(createMockPlayer(uuid, "Concurrent"));
            setPsychosis(uuid, kills);
            PsychosisLevel level = profileService.getViewQuick(PlayerId.of(uuid), configManager.snapshot()).psychosis();
            // Each level gets its own clock: a chat-only Low episode lasts 0 ticks, so a shared start
            // would land inside the previous check interval and the scheduler would rightly skip it.
            long now = 1_000_000L * kills;
            scheduler.tickAt(now);
            assertThat(dispatchedList).hasSize(config.presentation().maxConcurrent(level));
            assertThat(dispatchedList).extracting(DispatchedRecord::type).doesNotHaveDuplicates();
            var state = scheduler.getState(uuid);
            for (var record : dispatchedList) assertThat(state.getLastFiredMillis(record.type())).isEqualTo(now);
            long last = dispatchedList.stream().mapToLong(record -> config.presentation().scaled(level)
                    .durationTicks(record.type(), configManager.snapshot().config().sounds())).max().orElseThrow();
            long allowedAt = now + last * 50 + config.quietInterval(level).toMillis();
            assertThat(state.canStartEpisode(allowedAt - 1)).isFalse();
            assertThat(state.canStartEpisode(allowedAt)).isTrue();
            scheduler.tickAt(now + last * 50);
            assertThat(dispatchedList).hasSize(config.presentation().maxConcurrent(level));
        }
    }

    @Test void concurrencyAvailabilityKeepsCooldownsCapsAndFewerEffectsWithoutChargingSkips() {
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        for (AmbientEffectType type : List.of(AmbientEffectType.PARTICLES, AmbientEffectType.SCREEN_FLASH, AmbientEffectType.BOSS_BAR)) {
            yaml.set("effects." + type.configId() + ".enabled", true);
            yaml.set("effects." + type.configId() + ".session-cap", type == AmbientEffectType.PARTICLES ? 1 : 2);
        }
        var cfg = new EffectsConfigSection(Duration.ofMillis(1), disabled, disabled, disabled, disabled,
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 100,
                com.dasannn.socialblueprint.config.PresentationConfig.load(yaml));
        var state = new PlayerEffectState(); long now = 1_000_000L;
        state.recordFired(AmbientEffectType.PARTICLES, now - 100_000);
        state.recordFired(AmbientEffectType.SCREEN_FLASH, now - 1);
        for (PsychosisLevel level : List.of(PsychosisLevel.LOW, PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME)) {
            var candidates = AmbientEffectScheduler.availableEffects(cfg, state, level, now, new java.util.Random(42));
            assertThat(candidates).containsExactly(AmbientEffectType.BOSS_BAR);
        }
        assertThat(AmbientEffectScheduler.availableEffects(cfg, state, PsychosisLevel.NEUTRAL, now,
                new java.util.Random(42))).isEmpty();
        var original = configManager.snapshot();
        configManager.snapshotReference().set(new RuntimeSnapshot(original.config().withEffects(cfg), original.messages()));
        UUID uuid = UUID.randomUUID(); onlinePlayers.add(createMockPlayer(uuid, "Limited")); setPsychosis(uuid, 10);
        var actual = scheduler.getOrCreateState(uuid);
        actual.recordFired(AmbientEffectType.PARTICLES, now - 100_000);
        actual.recordFired(AmbientEffectType.SCREEN_FLASH, now - 1);
        scheduler.tickAt(now);
        assertThat(dispatchedList).extracting(DispatchedRecord::type).containsExactly(AmbientEffectType.BOSS_BAR);
        assertThat(actual.getSessionCount(AmbientEffectType.PARTICLES)).isEqualTo(1);
        assertThat(actual.getSessionCount(AmbientEffectType.SCREEN_FLASH)).isEqualTo(1);
        assertThat(actual.getSessionCount(AmbientEffectType.BOSS_BAR)).isEqualTo(1);
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

    @Test void ghostHistoryReadHopsToMainAndLateCompletionsCannotSurviveCleanup() throws Exception {
        RuntimeSnapshot original = configManager.snapshot();
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("effects.victim-ghost.enabled", true);
        yaml.set("effects.victim-ghost.cooldown-ticks", 1);
        SingleEffectConfig disabled = SingleEffectConfig.of(Duration.ZERO, 0);
        EffectsConfigSection config = new EffectsConfigSection(Duration.ofMillis(1), disabled, disabled, disabled, disabled,
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 100,
                com.dasannn.socialblueprint.config.PresentationConfig.load(yaml));
        configManager.snapshotReference().set(new RuntimeSnapshot(original.config().withEffects(config), original.messages()));
        java.util.concurrent.LinkedBlockingQueue<Runnable> mainQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        List<String> victims = new ArrayList<>();
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        AmbientEffectDispatcher ghostDispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                new FakeSilverfishService(null, registry, null)) {
            @Override public boolean dispatchVictimGhost(Player player, com.dasannn.socialblueprint.config.PresentationConfig.Ghost settings,
                                                        RuntimeSnapshot snapshot, String name) {
                player.getUniqueId(); // proxy asserts main-thread access
                victims.add(name);
                PsychosisLevel level = profileService.getViewQuick(PlayerId.of(player.getUniqueId()), snapshot).psychosis();
                assertThat(settings.durationTicks()).isEqualTo(level == PsychosisLevel.EXTREME ? 80 : 60);
                assertThat(snapshot).isSameAs(configManager.snapshot());
                return true;
            }
        };
        AmbientEffectScheduler ghostScheduler = new AmbientEffectScheduler(null, configManager, profileService,
                ghostDispatcher, () -> onlinePlayers, mainQueue::add);
        long now = 1_000_000L;
        for (String ending : List.of("delivery", "quit", "world-change", "disable", "unresolved", "medium", "low", "extreme")) {
            onlinePlayers.clear();
            victims.clear();
            UUID uuid = UUID.randomUUID();
            onlinePlayers.add(createMockPlayer(uuid, "Killer"));
            setPsychosis(uuid, ending.equals("medium") ? 2 : ending.equals("low") ? 0 : ending.equals("extreme") ? 10 : 5);
            PlayerId id = PlayerId.of(uuid);
            List<PsychosisEvent> before = psychosisRepo.findKillsByKillerSince(id, Instant.EPOCH);
            if (!before.isEmpty() && !ending.equals("unresolved"))
                new ProfileRepository(storage).saveAsync(com.dasannn.socialblueprint.domain.PlayerProfile.create(
                        before.getFirst().victim(), "KnownVictim", Instant.now())).join();
            ghostScheduler.tickAt(now);
            assertThat(victims).isEmpty();
            if (ending.equals("medium") || ending.equals("low")) {
                assertThat(mainQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            } else {
                Runnable completion = mainQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(completion).isNotNull();
                PlayerEffectState state = ghostScheduler.getOrCreateState(uuid);
                assertThat(state.getSessionCount(AmbientEffectType.VICTIM_GHOST)).isZero();
                switch (ending) {
                    case "quit" -> ghostScheduler.handlePlayerQuit(uuid);
                    case "world-change" -> ghostScheduler.handlePlayerWorldChange(uuid);
                    case "disable" -> ghostScheduler.stop();
                    default -> {}
                }
                completion.run();
                boolean delivered = ending.equals("delivery") || ending.equals("extreme");
                assertThat(victims).hasSize(delivered ? 1 : 0);
                assertThat(state.getSessionCount(AmbientEffectType.VICTIM_GHOST)).isEqualTo(delivered ? 1 : 0);
                if (delivered) {
                    assertThat(victims).containsExactly("KnownVictim");
                    assertThat(state.canStartEpisode(state.getLastFiredMillis(AmbientEffectType.VICTIM_GHOST) + 40 * 50L)).isFalse();
                    long duration = ending.equals("extreme") ? 80 : 60;
                    PsychosisLevel level = ending.equals("extreme") ? PsychosisLevel.EXTREME : PsychosisLevel.HIGH;
                    long allowedAt = state.getLastFiredMillis(AmbientEffectType.VICTIM_GHOST) + duration * 50L + config.quietInterval(level).toMillis();
                    assertThat(state.canStartEpisode(allowedAt - 1)).isFalse();
                    assertThat(state.canStartEpisode(allowedAt)).isTrue();
                    assertThat(state.canFire(AmbientEffectType.VICTIM_GHOST,
                            SingleEffectConfig.of(Duration.ZERO, 1), now + 1_000_000)).isFalse();
                }
            }
            assertThat(psychosisRepo.findKillsByKillerSince(id, Instant.EPOCH)).isEqualTo(before);
            now += 1_000_000L;
        }
    }

    @Test void toggleDuringPlaybackExpiresNormallyButKnownDirectionChangeCancels() throws Exception {
        for (AmbientEffectType type : AmbientEffectType.values())
            configManager.set("effects." + type.configId() + ".enabled", Boolean.toString(type == AmbientEffectType.BOSS_BAR));
        UUID uuid = UUID.randomUUID();
        Player player = createMockPlayer(uuid, "Subject");
        onlinePlayers.add(player);
        PlayerId id = PlayerId.of(uuid);
        for (boolean serenePlayback : List.of(false, true)) {
            for (boolean changeDirection : List.of(false, true)) {
                configManager.set("effects.boss-bar.enabled", "true");
                for (String effect : com.dasannn.socialblueprint.config.SerenityEffectsConfig.EFFECTS)
                    configManager.set("effects.serenity." + effect + ".enabled", Boolean.toString(effect.equals("apparition")));
                profileService.mind().resetAsync(id, PlayerId.CONSOLE, Instant.now()).join();
                profileService.mind().applyAsync(id, serenePlayback ? com.dasannn.socialblueprint.domain.MindInput.SLEEP
                        : com.dasannn.socialblueprint.domain.MindInput.DEATH,
                        new com.dasannn.socialblueprint.domain.MindInputConfig(true, 50, 50, 100), "setup", Instant.now()).join();
                profileService.loadViewAsync(id, "Subject", configManager.snapshot()).join();
                record Scheduled(Runnable action, long delay, java.util.concurrent.atomic.AtomicBoolean cancelled) {}
                List<Scheduled> tasks = new ArrayList<>();
                var restored = new java.util.concurrent.atomic.AtomicInteger();
                var registry = new AmbientEntityRegistry();
                var dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager,
                        new FakeSilverfishService(null, registry, null), (action, delay) -> {
                            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                            tasks.add(new Scheduled(action, delay, cancelled));
                            return () -> cancelled.set(true);
                        }) {
                    @Override public boolean dispatch(Player subject, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
                        assertThat(type).isEqualTo(AmbientEffectType.BOSS_BAR);
                        return startPresentation(uuid, type, config.presentation().bar().durationTicks(), restored::incrementAndGet) != null;
                    }
                    @Override public boolean dispatchSerene(Player subject, String effect, RuntimeSnapshot snapshot,
                            java.util.function.BooleanSupplier eligible) {
                        boolean started = startPresentation(uuid, AmbientEffectType.SCREEN_FLASH, 40, restored::incrementAndGet) != null;
                        guardDirection(uuid, eligible, 40);
                        return started;
                    }
                };
                var running = new AmbientEffectScheduler(null, configManager, profileService, dispatcher, () -> onlinePlayers);
                running.tickAt(1_000_000);
                assertThat(registry.presentationsFor(uuid)).hasSize(1);
                var expiry = tasks.getFirst();
                var guard = tasks.stream().filter(task -> task.delay() == 1).findFirst().orElseThrow();
                String toggleKey = serenePlayback ? "effects.serenity.apparition.enabled" : "effects.boss-bar.enabled";
                var blocked = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var blocking = com.dasannn.socialblueprint.storage.StorageTestSupport.blockExecutor(storage, blocked, release);
                try {
                    assertThat(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    configManager.set(toggleKey, "false");
                    assertThat(profileService.findViewCached(id, configManager.snapshot())).isEmpty();
                    running.tickAt(1_000_001);
                    guard.action().run();
                    assertThat(restored).hasValue(0);
                    assertThat(expiry.cancelled()).isFalse();
                } finally { release.countDown(); }
                blocking.join();
                profileService.loadViewAsync(id, "Subject", configManager.snapshot()).join();
                assertThat(running.directionStillMatches(id, serenePlayback)).isTrue();
                if (changeDirection) {
                    profileService.mind().resetAsync(id, PlayerId.CONSOLE, Instant.now()).join();
                    profileService.mind().applyAsync(id, serenePlayback ? com.dasannn.socialblueprint.domain.MindInput.DEATH
                            : com.dasannn.socialblueprint.domain.MindInput.SLEEP,
                            new com.dasannn.socialblueprint.domain.MindInputConfig(true, 50, 50, 100), "setup", Instant.now()).join();
                    profileService.loadViewAsync(id, "Subject", configManager.snapshot()).join();
                    assertThat(running.directionStillMatches(id, serenePlayback)).isFalse();
                    assertThat(running.directionStillMatches(id, !serenePlayback)).isTrue();
                    running.tickAt(1_000_002);
                    assertThat(expiry.cancelled()).isTrue();
                } else {
                    running.tickAt(1_000_002);
                    assertThat(restored).hasValue(0);
                    assertThat(expiry.cancelled()).isFalse();
                    expiry.action().run();
                }
                assertThat(restored).hasValue(1);
                assertThat(registry.presentationsFor(uuid)).isEmpty();
                dispatcher.cancelPending(uuid);
                assertThat(restored).hasValue(1);
            }
        }
    }
}
