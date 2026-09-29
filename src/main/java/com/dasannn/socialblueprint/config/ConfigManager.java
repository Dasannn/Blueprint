package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Manages loading, validation, persistence, and atomic reload of configuration per T-030, T-031, T-034, and T-035.
 * Replaces the runtime snapshot wholesale through a single atomic reference.
 * In-game edits validate before mutating disk or publishing snapshots (T-035).
 * Edits to config.yml preserve comments and formatting (T-035).
 * Player-visible messages in messages_<lang>.yml are editable in-game (SB-062).
 */
public class ConfigManager {

    private static final Set<String> SUPPORTED_CONFIG_LEAVES = createSupportedConfigLeaves();

    private final File configFile;
    private final Logger logger;
    private final Executor ioExecutor;
    private final MessageRegistry messageRegistry;
    private final AtomicReference<RuntimeSnapshot> snapshotRef;
    private final Object writeLock = new Object();

    public ConfigManager(File configFile, MessageRegistry messageRegistry, Executor ioExecutor, Logger logger) {
        this.configFile = Objects.requireNonNull(configFile, "configFile must not be null");
        this.messageRegistry = messageRegistry;
        this.ioExecutor = ioExecutor != null ? ioExecutor : Runnable::run;
        this.logger = logger != null ? logger : Logger.getLogger(ConfigManager.class.getName());
        this.snapshotRef = messageRegistry != null ? messageRegistry.snapshotReference() : new AtomicReference<>();
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
     * Validates through {@link PluginConfig#load(org.bukkit.configuration.ConfigurationSection)}
     * and loads matching {@link MessagesSnapshot}, publishing one combined {@link RuntimeSnapshot}.
     */
    public void reload() {
        synchronized (writeLock) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            PluginConfig newConfig = PluginConfig.load(yaml);
            File dataFolder = configFile.getParentFile();
            MessagesSnapshot newMessages = MessageRegistry.loadMessagesSnapshot(dataFolder, newConfig.language(), logger);
            RuntimeSnapshot newSnapshot = new RuntimeSnapshot(newConfig, newMessages);
            snapshotRef.set(newSnapshot);
        }
    }

    /**
     * Returns the current immutable configuration snapshot (T-030, T-034).
     */
    public PluginConfig config() {
        RuntimeSnapshot snap = snapshotRef.get();
        if (snap == null) {
            throw new IllegalStateException("ConfigManager has not been initialized");
        }
        return snap.config();
    }

    /**
     * Returns the current single atomic runtime snapshot (T-034).
     */
    public RuntimeSnapshot snapshot() {
        RuntimeSnapshot snap = snapshotRef.get();
        if (snap == null) {
            throw new IllegalStateException("ConfigManager has not been initialized");
        }
        return snap;
    }

    /**
     * Returns whether a path is a supported editable leaf (either in config.yml or messages).
     */
    public boolean isEditableKey(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String resolved = resolveConfigPath(path);
        if (SUPPORTED_CONFIG_LEAVES.contains(resolved)) {
            return true;
        }
        RuntimeSnapshot snap = snapshotRef.get();
        return snap != null && snap.messages().isKnownKey(path);
    }

    /**
     * Reads the current string representation of a configuration key or message key (T-035).
     */
    public String get(String path) {
        Objects.requireNonNull(path, "Configuration path must not be null");

        // 1. Check if it's a message key
        RuntimeSnapshot snap = snapshotRef.get();
        if (snap != null && snap.messages().isKnownKey(path)) {
            return snap.messages().resolveRaw(path, Collections.emptySet(), logger);
        }

        // 2. Check if it's a config.yml leaf
        String resolved = resolveConfigPath(path);
        if (SUPPORTED_CONFIG_LEAVES.contains(resolved)) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            if (yaml.contains(resolved)) {
                Object val = yaml.get(resolved);
                return val != null ? val.toString() : "";
            }
        }

        throw new ConfigValidationException(path, "Unknown or uneditable configuration key: " + path);
    }

    /**
     * In-game edit per T-035: validates through the EXACT same validation as file loading,
     * persists to disk atomically preserving comments and formatting, and publishes a new snapshot.
     * Rejects invalid values and unknown keys without modifying disk or running snapshot.
     */
    public void set(String path, String rawValue) {
        Objects.requireNonNull(path, "Configuration path must not be null");
        Objects.requireNonNull(rawValue, "Value must not be null");

        synchronized (writeLock) {
            RuntimeSnapshot current = snapshot();
            String resolvedConfigPath = resolveConfigPath(path);

            // Handle config.yml leaf edit
            if (SUPPORTED_CONFIG_LEAVES.contains(resolvedConfigPath)) {
                // 1. In-memory validation
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
                Object parsedValue = parseValue(rawValue);
                yaml.set(resolvedConfigPath, parsedValue);

                PluginConfig newConfig = PluginConfig.load(yaml);

                // 2. Persist atomically preserving comments and formatting
                try {
                    YamlFileUpdater.updateLeafAndSave(configFile, resolvedConfigPath, rawValue);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to persist updated configuration to disk: " + e.getMessage(), e);
                }

                // 3. Atomically update snapshot
                File dataFolder = configFile.getParentFile();
                MessagesSnapshot messages = newConfig.language().equals(current.config().language())
                        ? current.messages()
                        : MessageRegistry.loadMessagesSnapshot(dataFolder, newConfig.language(), logger);

                snapshotRef.set(new RuntimeSnapshot(newConfig, messages));
                return;
            }

            // Handle message leaf edit (SB-062, constitution §2.8)
            if (current.messages().isKnownKey(path)) {
                if (rawValue.isBlank()) {
                    throw new ConfigValidationException(path, "Message translation must not be blank");
                }
                ColorParser.validate(rawValue, path);

                File dataFolder = configFile.getParentFile();
                String activeLang = current.config().language();
                File messageFile = new File(dataFolder, "messages_" + activeLang + ".yml");
                if (!messageFile.exists()) {
                    // Ensure disk file exists before updating
                    MessageRegistry.loadMessagesSnapshot(dataFolder, activeLang, logger);
                }

                // Persist atomically to the active language file
                try {
                    YamlFileUpdater.updateLeafAndSave(messageFile, path, rawValue);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to persist updated message to disk: " + e.getMessage(), e);
                }

                // Reload messages and publish updated snapshot
                MessagesSnapshot updatedMessages = MessageRegistry.loadMessagesSnapshot(dataFolder, activeLang, logger);
                snapshotRef.set(new RuntimeSnapshot(current.config(), updatedMessages));
                return;
            }

            // Unknown or uneditable key
            throw new ConfigValidationException(path, "Unknown or uneditable configuration key: " + path);
        }
    }

    private String resolveConfigPath(String inputPath) {
        if (SUPPORTED_CONFIG_LEAVES.contains(inputPath)) {
            return inputPath;
        }
        if (SUPPORTED_CONFIG_LEAVES.contains("tiers." + inputPath)) {
            return "tiers." + inputPath;
        }
        if (inputPath.startsWith("tiers.") && SUPPORTED_CONFIG_LEAVES.contains(inputPath.substring("tiers.".length()))) {
            return inputPath;
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

        return raw;
    }

    private void extractBundledConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                if (configFile.getParentFile() != null) {
                    configFile.getParentFile().mkdirs();
                }
                Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.warning("[SocialBlueprint] Could not extract default config.yml: " + e.getMessage());
        }
    }

    private static Set<String> createSupportedConfigLeaves() {
        Set<String> set = new HashSet<>();
        set.add("language");
        set.add("chat-prefix");
        set.add("prefix");

        for (Tier tier : Tier.values()) {
            String tk = tier.configKey();
            set.add("tiers." + tk + ".prefix");
            set.add("tiers." + tk + ".threshold");
            set.add("tiers." + tk + ".repRequired");
        }

        set.add("confidence.half-life");
        set.add("confidence.low-threshold");
        set.add("confidence.established-threshold");
        set.add("confidence.high-threshold");

        set.add("psychosis.window");
        set.add("psychosis.medium-threshold");
        set.add("psychosis.high-threshold");
        set.add("psychosis.extreme-threshold");

        set.add("honor.cost");
        set.add("honor.window");
        set.add("honor.cooldown-per-pair");
        set.add("honor.max-per-target");

        set.add("permissions.show");
        set.add("permissions.show-others");
        set.add("permissions.give-reputation");
        set.add("permissions.take-reputation");
        set.add("permissions.view-reputation");
        set.add("permissions.admin-adjust");
        set.add("permissions.admin-config");

        return Collections.unmodifiableSet(set);
    }
}
