package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class AmbientEffectsListenerTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private AmbientEntityRegistry registry;
    private AmbientEffectsListener listener;
    private final AtomicBoolean schedulerQuitHandled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        registry = new AmbientEntityRegistry();

        File configFile = new File(tempDir, "config.yml");
        copyResource("config.yml", configFile);
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));

        Logger logger = Logger.getLogger("AmbientEffectsListenerTest-" + System.nanoTime());
        MessageRegistry messageRegistry = new MessageRegistry(tempDir, "en", logger);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        ReputationRepository reputationRepo = new ReputationRepository(storage, statusCache);
        PsychosisRepository psychosisRepo = new PsychosisRepository(storage);
        ProfileRepository profileRepo = new ProfileRepository(storage);

        ProfileService profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                id -> Optional.empty(),
                logger
        );

        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, logger);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(null, messageRegistry, configManager, silverfishService);

        AmbientEffectScheduler scheduler = new AmbientEffectScheduler(
                null, configManager, profileService, dispatcher, Collections::emptyList
        ) {
            @Override
            public void handlePlayerQuit(UUID playerId) {
                super.handlePlayerQuit(playerId);
                schedulerQuitHandled.set(true);
            }
        };

        listener = new AmbientEffectsListener(registry, scheduler);
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

    private Player mockPlayer(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return uuid;
                    return null;
                }
        );
    }

    private Entity mockEntity(UUID uuid) {
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return uuid;
                    if ("isValid".equals(method.getName())) return true;
                    return null;
                }
        );
    }

    private World mockWorld(String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    return null;
                }
        );
    }

    @Test
    @DisplayName("T-073: Player quit cleans registry and notifies scheduler")
    void playerQuitCleansRegistryAndScheduler() {
        UUID pid = UUID.randomUUID();
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(pid, 1, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        assertThat(registry.hasActiveEntities(pid)).isTrue();

        Player player = mockPlayer(pid);
        PlayerQuitEvent event = new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null);
        listener.onPlayerQuit(event);

        assertThat(registry.hasActiveEntities(pid)).isFalse();
        assertThat(schedulerQuitHandled.get()).isTrue();
    }

    @Test
    @DisplayName("T-073: Player world change cleans registry")
    void playerWorldChangeCleansRegistry() {
        UUID pid = UUID.randomUUID();
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(pid, 2, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        assertThat(registry.hasActiveEntities(pid)).isTrue();

        Player player = mockPlayer(pid);
        PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, mockWorld("world_nether"));
        listener.onPlayerChangedWorld(event);

        assertThat(registry.hasActiveEntities(pid)).isFalse();
    }

    @Test
    @DisplayName("T-072 / SB-042: Managed silverfish targeting is strictly cancelled")
    void managedEntityTargetingIsCancelled() {
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(UUID.randomUUID(), 3, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(ent, mockPlayer(UUID.randomUUID()), EntityTargetLivingEntityEvent.TargetReason.CLOSEST_PLAYER);
        listener.onEntityTarget(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.getTarget()).isNull();
    }

    @Test
    @DisplayName("T-072 / SB-042: Damage dealt by managed entity is strictly cancelled")
    void damageDealtByManagedEntityIsCancelled() {
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(UUID.randomUUID(), 4, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                ent, mockPlayer(UUID.randomUUID()), EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0
        );
        listener.onEntityDamageByEntity(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("T-072 / SB-042: Damage taken by managed entity is strictly cancelled")
    void damageTakenByManagedEntityIsCancelled() {
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(UUID.randomUUID(), 5, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        EntityDamageEvent event = new EntityDamageEvent(
                ent, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, 10.0
        );
        listener.onEntityDamage(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("T-072 / SB-042: Silverfish block infestation/changes are strictly cancelled")
    void blockChangeByManagedEntityIsCancelled() {
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(UUID.randomUUID(), 6, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        EntityChangeBlockEvent event = new EntityChangeBlockEvent(
                ent, (org.bukkit.block.Block) null, (org.bukkit.block.data.BlockData) null
        );
        listener.onEntityChangeBlock(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("T-072 / SB-042: Managed entity death drops and XP are completely cleared")
    void deathDropsAndXpCleared() {
        Entity ent = mockEntity(UUID.randomUUID());
        ActiveEntityEntry entry = new ActiveEntityEntry(UUID.randomUUID(), 7, ent, UUID.randomUUID(), null, null);
        registry.register(entry);

        List<ItemStack> drops = new ArrayList<>();
        EntityDeathEvent event = new EntityDeathEvent(
                (org.bukkit.entity.LivingEntity) Proxy.newProxyInstance(
                        Entity.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.entity.LivingEntity.class},
                        (proxy, method, args) -> {
                            if ("getUniqueId".equals(method.getName())) return ent.getUniqueId();
                            return null;
                        }
                ),
                (org.bukkit.damage.DamageSource) null,
                drops,
                50 // 50 XP
        );
        listener.onEntityDeath(event);

        assertThat(event.getDrops()).isEmpty();
        assertThat(event.getDroppedExp()).isEqualTo(0);
    }
}
