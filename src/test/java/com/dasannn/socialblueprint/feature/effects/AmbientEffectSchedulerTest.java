package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SingleEffectConfig;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
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
    private AmbientEffectScheduler scheduler;
    private final List<DispatchedRecord> dispatchedList = new ArrayList<>();
    private final List<Player> onlinePlayers = new ArrayList<>();

    private record DispatchedRecord(Player player, AmbientEffectType type, long timestamp) {}

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
        SingleEffectConfig silverfish = new SingleEffectConfig(Duration.ofSeconds(60), 2, 40, List.of());
        SingleEffectConfig whisper = new SingleEffectConfig(Duration.ofSeconds(30), 3, 0, List.of());
        SingleEffectConfig creeper = new SingleEffectConfig(Duration.ofSeconds(120), 1, 0, List.of());
        SingleEffectConfig fakeAnnounce = new SingleEffectConfig(Duration.ofSeconds(300), 1, 0, List.of("FakeUser"));

        EffectsConfigSection effectsCfg = new EffectsConfigSection(
                -10,
                Duration.ofSeconds(30),
                silverfish,
                whisper,
                creeper,
                fakeAnnounce
        );

        PluginConfig fullConfig = configManager.config().withEffects(effectsCfg);
        configManager.snapshotReference().set(new RuntimeSnapshot(fullConfig, configManager.snapshot().messages()));

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        PsychosisRepository psychosisRepo = new PsychosisRepository(storage);
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
            public void dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
                dispatchedList.add(new DispatchedRecord(player, type, System.currentTimeMillis()));
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
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getUniqueId".equals(m)) return uuid;
                    if ("getName".equals(m)) return name;
                    if ("isOnline".equals(m)) return true;
                    return null;
                }
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

    @Test
    @DisplayName("T-070 / SB-040: Status at or above threshold never triggers ambient effects")
    void statusAtOrAboveThresholdNeverFires() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        onlinePlayers.add(createMockPlayer(p1, "AtThreshold"));
        onlinePlayers.add(createMockPlayer(p2, "AboveThreshold"));
        onlinePlayers.add(createMockPlayer(p3, "PositiveStatus"));

        setPlayerStatus(p1, -10); // Exactly at threshold (-10), not below
        setPlayerStatus(p2, -5);  // Above threshold
        setPlayerStatus(p3, 20);  // High status

        scheduler.tickAt(100_000L);

        assertThat(dispatchedList).isEmpty();
    }

    @Test
    @DisplayName("T-070 / SB-040: Status strictly below threshold fires an eligible ambient effect")
    void statusBelowThresholdFiresEffect() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "LowStatusPlayer");
        onlinePlayers.add(player);

        setPlayerStatus(p, -11); // Below -10 threshold

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
    @DisplayName("T-075 / SB-044: Opted-out players never receive any ambient effect")
    void optedOutPlayersNeverReceiveEffects() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "OptedOutPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(p, -50); // Deep in low status

        // Toggle opt-out to true
        profileService.toggleEffectsOptOutAsync(PlayerId.of(p), "OptedOutPlayer").join();

        scheduler.tickAt(100_000L);

        assertThat(dispatchedList).isEmpty();
    }

    @Test
    @DisplayName("T-070 / SB-043: Player quit resets session counts so caps refresh for next session")
    void playerQuitResetsSessionCaps() {
        UUID p = UUID.randomUUID();
        Player player = createMockPlayer(p, "QuittingPlayer");
        onlinePlayers.add(player);
        setPlayerStatus(p, -20);

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
}
