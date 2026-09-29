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
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

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
    private ProfileService profileService;

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

        // Initialize StorageEngine and run SQLite migrations (T-018, ARCHITECTURE.md §4, §5)
        File dbFile = new File(getDataFolder(), "socialblueprint.db");
        this.storageEngine = StorageEngine.open("jdbc:sqlite:" + dbFile.getAbsolutePath());
        this.storageEngine.runMigrations();

        // Initialize repositories and in-memory status cache (T-018, T-019)
        this.statusCache = new StatusCache();
        this.reputationRepository = new ReputationRepository(storageEngine, statusCache);
        this.profileRepository = new ProfileRepository(storageEngine);
        this.psychosisRepository = new PsychosisRepository(storageEngine);
        this.auditRepository = new AuditRepository(storageEngine);

        // Initialize ProfileService (T-042, T-045)
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

        // Register event listeners (T-040, T-041, T-042, T-043, T-044)
        getServer().getPluginManager().registerEvents(
                new AsyncChatListener(profileService, configManager, messageRegistry),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(profileService, configManager),
                this
        );

        // Register /status command dispatcher (T-035, T-045)
        PluginCommand statusCmd = getCommand("status");
        if (statusCmd != null) {
            StatusCommandExecutor executor = new StatusCommandExecutor(
                    configManager,
                    messageRegistry,
                    profileService,
                    this
            );
            statusCmd.setExecutor(executor);
            statusCmd.setTabCompleter(executor);
        }

        getLogger().info("SocialBlueprint enabled.");
    }

    @Override
    public void onDisable() {
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
}
