package com.dasannn.socialblueprint;

import com.dasannn.socialblueprint.command.StatusCommandExecutor;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.ConfigValidationException;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.platform.BukkitPlayerLookup;
import com.dasannn.socialblueprint.platform.listener.AsyncChatListener;
import com.dasannn.socialblueprint.platform.listener.PlayerLifecycleListener;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import com.dasannn.socialblueprint.storage.StorageLifecycleCoordinator;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.legacy.LegacyImportService;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.dasannn.socialblueprint.feature.effects.AmbientEffectsListener;
import com.dasannn.socialblueprint.feature.effects.AmbientEntityRegistry;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectDispatcher;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectScheduler;
import com.dasannn.socialblueprint.feature.effects.FakeSilverfishService;
import java.time.Clock;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import java.io.File;
import java.util.Optional;
import java.util.UUID;

public final class SocialBlueprintPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private StorageEngine storageEngine;
    private StatusCache statusCache;
    private ReputationRepository reputationRepository;
    private ProfileRepository profileRepository;
    private PsychosisRepository psychosisRepository;
    private AuditRepository auditRepository;
    private CompensationRepository compensationRepository;
    private com.dasannn.socialblueprint.storage.DuelRepository duelRepository;
    private com.dasannn.socialblueprint.feature.duel.DuelService duelService;
    private ProfileService profileService;
    private HonorService honorService;
    private com.dasannn.socialblueprint.feature.update.UpdateService updateService;
    private LegacyImportService legacyImportService;
    private Economy economy;
    private com.dasannn.socialblueprint.storage.RaterRevealRepository raterRevealRepository;
    private com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService;
    private AmbientEntityRegistry ambientEntityRegistry;
    private FakeSilverfishService fakeSilverfishService;
    private AmbientEffectDispatcher ambientEffectDispatcher;
    private AmbientEffectScheduler ambientEffectScheduler;

    @Override
    public void onEnable() {
        // Ensure data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Initialize MessageRegistry and ConfigManager
        this.messageRegistry = new MessageRegistry(getDataFolder(), "en", getLogger());

        // Finding 1 (threading audit): configuration loading and defaults merging run
        // synchronously on the main thread during enable. During enable the server is not
        // ticking and no player is connected, so there is no tick to stall; moving
        // configuration loading off the main thread would buy nothing and risk a
        // half-initialised plugin.
        File configFile = new File(getDataFolder(), "config.yml");
        this.configManager = new ConfigManager(
                configFile,
                messageRegistry,
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTaskAsynchronously(this, runnable);
                    } else {
                        runnable.run();
                    }
                },
                () -> getDescription().getVersion(),
                getLogger()
        );

        // Load and validate configuration strictly per T-031
        try {
            configManager.initialize();
        } catch (ConfigValidationException e) {
            getLogger().severe("Failed to enable SocialBlueprint: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            throw new IllegalStateException("Failed to enable SocialBlueprint due to invalid configuration: " + e.getMessage(), e);
        }

        // Resolve Vault Economy provider per SB-051 and T-057
        if (economy == null) {
            setupEconomy();
        }
        if (economy == null) {
            getLogger().severe("Failed to enable SocialBlueprint: No Vault economy provider was found. SocialBlueprint requires Vault and an economy plugin (SB-051).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize StorageEngine and run SQLite migrations on storage executor (T-018, ARCHITECTURE.md §4, §5)
        File dbFile = new File(getDataFolder(), "socialblueprint.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                getLogger(),
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                },
                () -> {
                    if (isEnabled()) {
                        getServer().getPluginManager().disablePlugin(this);
                    }
                },
                this::isEnabled
        );

        coordinator.start(jdbcUrl, engine -> {
            com.dasannn.socialblueprint.storage.DuelRepository startupDuelRepo = new com.dasannn.socialblueprint.storage.DuelRepository(engine);
            startupDuelRepo.cleanupStaleDuelsOnStartupAsync(java.time.Instant.now())
                    .whenComplete((count, error) -> {
                        if (!isEnabled()) {
                            engine.close();
                            return;
                        }
                        if (error != null) {
                            getLogger().severe("Failed to clean up stale duels on startup: " + error.getMessage());
                            getServer().getPluginManager().disablePlugin(this);
                            return;
                        }
                        getServer().getScheduler().runTask(this, () -> completeInitialization(engine));
                    });
        });
    }

    private void setupEconomy() {
        if (getServer().getPluginManager() == null || getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        if (getServer().getServicesManager() != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.economy = rsp.getProvider();
            }
        }
    }

    void completeInitialization(StorageEngine engine) {
        if (!isEnabled()) {
            if (engine != null) {
                engine.close();
            }
            return;
        }
        this.storageEngine = engine;
        this.statusCache = new StatusCache(
                configManager.config().decay().cacheTtl(),
                Clock.systemUTC(),
                () -> configManager.config().decay().toDomain()
        );
        this.reputationRepository = new ReputationRepository(
                storageEngine,
                statusCache,
                () -> configManager.config().decay().toDomain()
        );
        this.profileRepository = new ProfileRepository(storageEngine);
        this.psychosisRepository = new PsychosisRepository(storageEngine);
        this.auditRepository = new AuditRepository(storageEngine);
        this.compensationRepository = new CompensationRepository(storageEngine);
        this.raterRevealRepository = new com.dasannn.socialblueprint.storage.RaterRevealRepository(storageEngine);
        this.duelRepository = new com.dasannn.socialblueprint.storage.DuelRepository(storageEngine);

        BukkitPlayerLookup playerLookup = new BukkitPlayerLookup(getServer());
        this.profileService = new ProfileService(
                storageEngine,
                reputationRepository,
                psychosisRepository,
                profileRepository,
                statusCache,
                configManager,
                playerLookup,
                getLogger()
        );

        this.configManager.addSnapshotListener(snapshot -> {
            if (this.statusCache != null) {
                this.statusCache.updateTtl(snapshot.config().decay().cacheTtl());
            }
            if (this.profileService != null) {
                this.profileService.invalidateAll();
            }
        });

        this.honorService = new HonorService(
                configManager,
                messageRegistry,
                reputationRepository,
                auditRepository,
                compensationRepository,
                profileService,
                economy,
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                }
        );
        this.honorService.reconcileCompensationsAsync();

        this.statusGuiService = new com.dasannn.socialblueprint.feature.gui.StatusGuiService(
                messageRegistry,
                profileService,
                reputationRepository,
                raterRevealRepository,
                honorService,
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                },
                economy,
                uuid -> {
                    if (getServer() != null) {
                        return getServer().getOfflinePlayer(uuid);
                    }
                    return null;
                },
                java.time.Clock.systemUTC(),
                new com.dasannn.socialblueprint.feature.gui.GuiRenderer(messageRegistry, uuid -> {
                    if (getServer() != null) {
                        return getServer().getOfflinePlayer(uuid);
                    }
                    return null;
                }),
                compensationRepository,
                getLogger()
        );

        this.duelService = new com.dasannn.socialblueprint.feature.duel.DuelService(
                duelRepository,
                auditRepository,
                psychosisRepository,
                configManager,
                messageRegistry,
                playerLookup,
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                },
                (delay, task) -> {
                    if (isEnabled()) {
                        org.bukkit.scheduler.BukkitTask bt = getServer().getScheduler().runTaskLater(
                                this,
                                task,
                                Math.max(1L, delay.toMillis() / 50L)
                        );
                        return bt::cancel;
                    }
                    return () -> {};
                },
                (playerId, component) -> {
                    org.bukkit.entity.Player p = getServer().getPlayer(playerId.uuid());
                    if (p != null && p.isOnline()) {
                        p.sendMessage(component);
                    }
                },
                component -> getServer().broadcast(component)
        );

        getServer().getPluginManager().registerEvents(
                new AsyncChatListener(profileService, configManager, messageRegistry, statusGuiService),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(profileService, configManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new com.dasannn.socialblueprint.platform.listener.DuelCombatListener(
                        duelService,
                        psychosisRepository,
                        configManager,
                        reputationRepository
                ),
                this
        );

        // Initialize Ambient Effects (SB-040 to SB-044)
        this.ambientEntityRegistry = new AmbientEntityRegistry();
        this.fakeSilverfishService = new FakeSilverfishService(this, ambientEntityRegistry, getLogger());
        this.ambientEffectDispatcher = new AmbientEffectDispatcher(this, messageRegistry, configManager, fakeSilverfishService);
        this.ambientEffectScheduler = new AmbientEffectScheduler(
                this,
                configManager,
                profileService,
                ambientEffectDispatcher,
                getServer()::getOnlinePlayers
        );
        this.ambientEffectScheduler.start();

        getServer().getPluginManager().registerEvents(
                new AmbientEffectsListener(ambientEntityRegistry, ambientEffectScheduler),
                this
        );
        getServer().getPluginManager().registerEvents(
                new com.dasannn.socialblueprint.platform.listener.StatusGuiListener(statusGuiService, getLogger()),
                this
        );

        this.updateService = new com.dasannn.socialblueprint.feature.update.UpdateService(
                configManager,
                messageRegistry,
                storageEngine::submitAsync,
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                },
                () -> getServer().getUpdateFolderFile(),
                () -> getDescription().getVersion(),
                this::getFile,
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .build(),
                getLogger()
        );

        this.legacyImportService = new LegacyImportService(
                storageEngine,
                reputationRepository,
                profileRepository,
                auditRepository,
                messageRegistry,
                playerLookup,
                getDataFolder(),
                runnable -> {
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, runnable);
                    }
                },
                () -> getServer().getConsoleSender(),
                getLogger()
        );

        PluginCommand statusCmd = getCommand("status");
        if (statusCmd != null) {
            StatusCommandExecutor executor = new StatusCommandExecutor(
                    configManager,
                    messageRegistry,
                    profileService,
                    honorService,
                    duelService,
                    auditRepository,
                    ambientEntityRegistry::cleanForPlayer,
                    legacyImportService,
                    this,
                    updateService,
                    statusGuiService
            );
            statusCmd.setExecutor(executor);
            statusCmd.setTabCompleter(executor);
        }

        this.updateService.onStartup(configManager.snapshot());

        getLogger().info("SocialBlueprint enabled.");
    }

    @Override
    public void onDisable() {
        if (duelService != null) {
            duelService.shutdown();
        }
        if (ambientEffectScheduler != null) {
            ambientEffectScheduler.stop();
        }
        if (ambientEntityRegistry != null) {
            ambientEntityRegistry.cleanAll();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        getLogger().info("SocialBlueprint disabled.");
    }

    public AmbientEntityRegistry getAmbientEntityRegistry() {
        return ambientEntityRegistry;
    }

    public FakeSilverfishService getFakeSilverfishService() {
        return fakeSilverfishService;
    }

    public AmbientEffectDispatcher getAmbientEffectDispatcher() {
        return ambientEffectDispatcher;
    }

    public AmbientEffectScheduler getAmbientEffectScheduler() {
        return ambientEffectScheduler;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageRegistry getMessageRegistry() {
        return messageRegistry;
    }

    public PluginConfig getConfiguration() {
        return configManager != null ? configManager.config() : null;
    }

    public StorageEngine getStorageEngine() {
        return storageEngine;
    }

    public ProfileService getProfileService() {
        return profileService;
    }

    public ReputationRepository getReputationRepository() {
        return reputationRepository;
    }

    public ProfileRepository getProfileRepository() {
        return profileRepository;
    }

    public PsychosisRepository getPsychosisRepository() {
        return psychosisRepository;
    }

    public AuditRepository getAuditRepository() {
        return auditRepository;
    }

    public StatusCache getStatusCache() {
        return statusCache;
    }

    public HonorService getHonorService() {
        return honorService;
    }

    public LegacyImportService getLegacyImportService() {
        return legacyImportService;
    }

    public CompensationRepository getCompensationRepository() {
        return compensationRepository;
    }

    public Economy getEconomy() {
        return economy;
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
        if (honorService != null) {
            honorService.setEconomy(economy);
            honorService.reconcileCompensationsAsync();
        }
        if (statusGuiService != null) {
            statusGuiService.setEconomy(economy);
        }
    }

    public com.dasannn.socialblueprint.storage.RaterRevealRepository getRaterRevealRepository() {
        return raterRevealRepository;
    }

    public com.dasannn.socialblueprint.feature.gui.StatusGuiService getStatusGuiService() {
        return statusGuiService;
    }

    public com.dasannn.socialblueprint.storage.DuelRepository getDuelRepository() {
        return duelRepository;
    }

    public com.dasannn.socialblueprint.feature.duel.DuelService getDuelService() {
        return duelService;
    }

    public com.dasannn.socialblueprint.feature.update.UpdateService getUpdateService() {
        return updateService;
    }

    public File getPluginFile() {
        return getFile();
    }
}
