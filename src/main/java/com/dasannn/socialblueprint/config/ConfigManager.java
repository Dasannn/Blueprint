package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Manages loading, validation, persistence, and atomic reload of configuration per T-030, T-031, T-034, and T-035.
 * Replaces the snapshot wholesale (T-034); nothing caches derived values across reload.
 * All in-game edits validate before persisting and publishing (T-035).
 */
public class ConfigManager {

    private final File configFile;
    private final Logger logger;
    private final Executor ioExecutor;
    private final MessageRegistry messageRegistry;
    private final AtomicReference<PluginConfig> currentConfig = new AtomicReference<>();
    private final Object writeLock = new Object();

    public ConfigManager(File configFile, MessageRegistry messageRegistry, Executor ioExecutor, Logger logger) {
        this.configFile = Objects.requireNonNull(configFile, "configFile must not be null");
        this.messageRegistry = messageRegistry;
        this.ioExecutor = ioExecutor != null ? ioExecutor : Runnable::run;
        this.logger = logger != null ? logger : Logger.getLogger(ConfigManager.class.getName());
    }

    /**
     * Initializes configuration from disk. Extracts bundled config.yml if not present on first run.
     * Validates strictly on load; throws {@link ConfigValidationException} naming the key on failure (T-031).
     */
    public void initialize() {
        if (!configFile.exists()) {
            extractBundledConfig();
        }
        reload();
    }

    /**
     * Reloads configuration from disk atomically (T-034).
     * Validates through {@link PluginConfig#load(org.bukkit.configuration.ConfigurationSection)}.
     */
    public void reload() {
        synchronized (writeLock) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            PluginConfig newSnapshot = PluginConfig.load(yaml);
            currentConfig.set(newSnapshot);
            if (messageRegistry != null) {
                messageRegistry.setLanguage(newSnapshot.language());
            }
        }
    }

    /**
     * Returns the current immutable configuration snapshot (T-030, T-034).
     */
    public PluginConfig config() {
        PluginConfig cfg = currentConfig.get();
        if (cfg == null) {
            throw new IllegalStateException("ConfigManager has not been initialized");
        }
        return cfg;
    }

    /**
     * Reads the current string representation of a configuration key (T-035).
     */
    public String get(String path) {
        Objects.requireNonNull(path, "Configuration path must not be null");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        String resolvedPath = resolvePath(yaml, path);

        if (!yaml.contains(resolvedPath)) {
            throw new ConfigValidationException(path, "Unknown configuration key: " + path);
        }
        Object val = yaml.get(resolvedPath);
        return val != null ? val.toString() : "";
    }

    /**
     * In-game edit per T-035: validates through the EXACT same validation as file loading,
     * persists to disk, and publishes a new snapshot atomically.
     * Rejects invalid values without persisting or modifying the running snapshot.
     */
    public void set(String path, String rawValue) {
        Objects.requireNonNull(path, "Configuration path must not be null");
        Objects.requireNonNull(rawValue, "Value must not be null");

        synchronized (writeLock) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            String resolvedPath = resolvePath(yaml, path);

            Object parsedValue = parseValue(rawValue);
            yaml.set(resolvedPath, parsedValue);

            // Validate the updated configuration through the exact same load logic (T-035)
            PluginConfig newSnapshot = PluginConfig.load(yaml);

            // Validation passed! Save to disk and update snapshot atomically
            try {
                yaml.save(configFile);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to persist updated configuration to disk: " + e.getMessage(), e);
            }

            currentConfig.set(newSnapshot);
            if (messageRegistry != null) {
                messageRegistry.setLanguage(newSnapshot.language());
            }
        }
    }

    private String resolvePath(YamlConfiguration yaml, String inputPath) {
        if (yaml.contains(inputPath)) {
            return inputPath;
        }
        // If user typed "tier-4.threshold" but it's under "tiers.tier-4.threshold"
        if (yaml.contains("tiers." + inputPath)) {
            return "tiers." + inputPath;
        }
        // If user typed "tiers.tier-4.threshold" but it's under "tier-4.threshold"
        if (inputPath.startsWith("tiers.") && yaml.contains(inputPath.substring("tiers.".length()))) {
            return inputPath.substring("tiers.".length());
        }
        return inputPath;
    }

    private Object parseValue(String raw) {
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(trimmed)) return Boolean.FALSE;

        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
        }

        return trimmed;
    }

    private void extractBundledConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                configFile.getParentFile().mkdirs();
                Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.warning("[SocialBlueprint] Could not extract default config.yml: " + e.getMessage());
        }
    }
}
