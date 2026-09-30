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
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.dasannn.socialblueprint.storage.CompensationRepository;
import java.io.File;

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
    private Economy economy;

    @Override
    public void onEnable() {
        // Ensure data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Initialize MessageRegistry and ConfigManager
        this.messageRegistry = new MessageRegistry(getDataFolder(), "en", getLogger());

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

        coordinator.start(jdbcUrl, this::completeInitialization);
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
        this.statusCache = new StatusCache();
        this.reputationRepository = new ReputationRepository(storageEngine, statusCache);
        this.profileRepository = new ProfileRepository(storageEngine);
        this.psychosisRepository = new PsychosisRepository(storageEngine);
        this.auditRepository = new AuditRepository(storageEngine);
        this.compensationRepository = new CompensationRepository(storageEngine);
        this.duelRepository = new com.dasannn.socialblueprint.storage.DuelRepository(storageEngine);
        this.duelRepository.cleanupStaleDuelsOnStartup(java.time.Instant.now());

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
                new AsyncChatListener(profileService, configManager, messageRegistry),
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
                        configManager
                ),
                this
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
                    this
            );
            statusCmd.setExecutor(executor);
            statusCmd.setTabCompleter(executor);
        }

        getLogger().info("SocialBlueprint enabled.");
    }

    @Override
    public void onDisable() {
        if (duelService != null) {
            duelService.shutdown();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        getLogger().info("SocialBlueprint disabled.");
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
    }

    public com.dasannn.socialblueprint.storage.DuelRepository getDuelRepository() {
        return duelRepository;
    }

    public com.dasannn.socialblueprint.feature.duel.DuelService getDuelService() {
        return duelService;
    }
}
