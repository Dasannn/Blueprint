package com.dasannn.socialblueprint;

import com.dasannn.socialblueprint.command.StatusCommandExecutor;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.ConfigValidationException;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.PluginConfig;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SocialBlueprintPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageRegistry messageRegistry;

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

        // Register /status command dispatcher (T-035)
        PluginCommand statusCmd = getCommand("status");
        if (statusCmd != null) {
            StatusCommandExecutor executor = new StatusCommandExecutor(configManager, messageRegistry);
            statusCmd.setExecutor(executor);
            statusCmd.setTabCompleter(executor);
        }

        getLogger().info("SocialBlueprint enabled.");
    }

    @Override
    public void onDisable() {
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
}
