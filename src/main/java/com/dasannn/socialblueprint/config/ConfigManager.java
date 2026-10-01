package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final java.util.function.Supplier<String> versionSupplier;
    private final java.util.List<java.util.function.Consumer<RuntimeSnapshot>> snapshotListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    public ConfigManager(File configFile, MessageRegistry messageRegistry, Executor ioExecutor, java.util.function.Supplier<String> versionSupplier, Logger logger) {
        this.configFile = Objects.requireNonNull(configFile, "configFile must not be null");
        this.messageRegistry = messageRegistry;
        this.ioExecutor = ioExecutor != null ? ioExecutor : Runnable::run;
        this.logger = logger != null ? logger : Logger.getLogger(ConfigManager.class.getName());
        this.versionSupplier = versionSupplier != null ? versionSupplier : ConfigManager::resolveBundledVersion;
        this.snapshotRef = messageRegistry != null ? messageRegistry.snapshotReference() : new AtomicReference<>();
    }

    public ConfigManager(File configFile, MessageRegistry messageRegistry, Executor ioExecutor, Logger logger) {
        this(configFile, messageRegistry, ioExecutor, null, logger);
    }

    public void addSnapshotListener(java.util.function.Consumer<RuntimeSnapshot> listener) {
        if (listener != null) {
            snapshotListeners.add(listener);
        }
    }

    private void notifySnapshotListeners(RuntimeSnapshot snapshot) {
        for (java.util.function.Consumer<RuntimeSnapshot> listener : snapshotListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                logger.warning("[SocialBlueprint] Snapshot listener threw exception: " + e.getMessage());
            }
        }
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
    public RuntimeSnapshot reload() {
        synchronized (writeLock) {
            migrateLegacyHonorWindowIfNeeded(configFile, logger);
            File dataFolder = configFile.getParentFile();
            ConfigMerger.mergeMissingDefaults(configFile, dataFolder, versionSupplier.get(), logger);

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            PluginConfig newConfig = PluginConfig.load(yaml);
            MessagesSnapshot newMessages = MessageRegistry.loadMessagesSnapshot(dataFolder, newConfig.language(), logger);
            RuntimeSnapshot newSnapshot = new RuntimeSnapshot(newConfig, newMessages);
            snapshotRef.set(newSnapshot);
            notifySnapshotListeners(newSnapshot);
            return newSnapshot;
        }
    }

    public AtomicReference<RuntimeSnapshot> snapshotReference() {
        return snapshotRef;
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
        return isEditableKey(snapshot(), path);
    }

    public boolean isEditableKey(RuntimeSnapshot snapshot, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String resolved = resolveConfigPath(path);
        if (isSupportedConfigLeaf(resolved)) {
            return true;
        }
        return snapshot != null && snapshot.messages().isKnownKey(path);
    }

    /**
     * Reads the current string representation of a configuration key or message key (T-035).
     */
    public String get(String path) {
        return get(snapshot(), path);
    }

    public String get(RuntimeSnapshot snapshot, String path) {
        Objects.requireNonNull(path, "Configuration path must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // 1. Check if it's a message key
        if (snapshot.messages().isKnownKey(path)) {
            Set<String> warned = messageRegistry != null ? messageRegistry.warnedKeys() : Collections.newSetFromMap(new ConcurrentHashMap<>());
            return snapshot.messages().resolveRaw(path, warned, logger);
        }

        // 2. Check if it's a config.yml leaf
        String resolved = resolveConfigPath(path);
        if (isSupportedConfigLeaf(resolved)) {
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
    public RuntimeSnapshot set(String path, String rawValue) {
        Objects.requireNonNull(path, "Configuration path must not be null");
        Objects.requireNonNull(rawValue, "Value must not be null");

        synchronized (writeLock) {
            RuntimeSnapshot current = snapshot();
            String resolvedConfigPath = resolveConfigPath(path);

            // Handle config.yml leaf edit
            if (isSupportedConfigLeaf(resolvedConfigPath)) {
                // 1. In-memory validation
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
                Object parsedValue = parseValueForPath(resolvedConfigPath, rawValue);
                yaml.set(resolvedConfigPath, parsedValue);

                PluginConfig newConfig = PluginConfig.load(yaml);

                // 2. Persist atomically preserving comments and formatting
                try {
                    YamlFileUpdater.updateLeafAndSave(configFile, resolvedConfigPath, formatRawValueForPath(resolvedConfigPath, rawValue));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to persist updated configuration to disk: " + e.getMessage(), e);
                }

                // 3. Atomically update snapshot
                File dataFolder = configFile.getParentFile();
                MessagesSnapshot messages = newConfig.language().equals(current.config().language())
                        ? current.messages()
                        : MessageRegistry.loadMessagesSnapshot(dataFolder, newConfig.language(), logger);

                RuntimeSnapshot newSnapshot = new RuntimeSnapshot(newConfig, messages);
                snapshotRef.set(newSnapshot);
                notifySnapshotListeners(newSnapshot);
                return newSnapshot;
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
                RuntimeSnapshot newSnapshot = new RuntimeSnapshot(current.config(), updatedMessages);
                snapshotRef.set(newSnapshot);
                notifySnapshotListeners(newSnapshot);
                return newSnapshot;
            }

            // Unknown or uneditable key
            throw new ConfigValidationException(path, "Unknown or uneditable configuration key: " + path);
        }
    }

    public CompletableFuture<RuntimeSnapshot> setAsync(String path, String rawValue) {
        return CompletableFuture.supplyAsync(() -> set(path, rawValue), ioExecutor);
    }

    public Executor ioExecutor() {
        return ioExecutor;
    }

    public static void migrateLegacyHonorWindowIfNeeded(File configFile, Logger logger) {
        if (configFile == null || !configFile.exists()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            if (yaml.contains("honor.window") && !yaml.contains("honor.multiplier-window") && !yaml.contains("honor.cap-window")) {
                String oldWindow = yaml.getString("honor.window");
                String defaultMult = "1h";
                String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
                // Anchor to the honor section. psychosis also has a window key
                // and appears first in the shipped file, so an unanchored search
                // rewrites psychosis and leaves honor in place, destroying a
                // working configuration on upgrade.
                Matcher honorSection = Pattern.compile("(?m)^honor:[ \t]*$").matcher(content);
                int searchFrom = honorSection.find() ? honorSection.end() : 0;
                Pattern pattern = Pattern.compile("(?m)^([ \t]+)window:[ \t]*(.*)$");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find(searchFrom)) {
                    String indent = matcher.group(1);
                    String lineSep = content.contains("\r\n") ? "\r\n" : "\n";
                    String replacement = indent + "multiplier-window: " + defaultMult + lineSep + indent + "cap-window: " + matcher.group(2).trim();
                    String updated = content.substring(0, matcher.start()) + replacement + content.substring(matcher.end());
                    Path targetPath = configFile.toPath();
                    Path tempPath = targetPath.resolveSibling(configFile.getName() + ".tmp." + UUID.randomUUID());
                    Files.writeString(tempPath, updated, StandardCharsets.UTF_8);
                    try {
                        Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tempPath);
                    }
                } else {
                    yaml.set("honor.multiplier-window", defaultMult);
                    yaml.set("honor.cap-window", oldWindow);
                    yaml.set("honor.window", null);
                    yaml.save(configFile);
                }
                if (logger != null) {
                    logger.info("[SocialBlueprint] Migrated legacy 'honor.window: " + oldWindow + "' to 'honor.cap-window: " + oldWindow + "' and 'honor.multiplier-window: " + defaultMult + "'");
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[SocialBlueprint] Failed to migrate legacy honor.window configuration: " + e.getMessage());
            }
        }
    }

    private boolean isSupportedConfigLeaf(String path) {
        if (SUPPORTED_CONFIG_LEAVES.contains(path)) {
            return true;
        }
        if (path.startsWith("sounds.")) {
            String[] parts = path.split("\\.");
            if (parts.length == 3) {
                String leaf = parts[2];
                return leaf.equals("key") || leaf.equals("volume") || leaf.equals("pitch") || leaf.equals("category") || leaf.equals("delay");
            }
        }
        return false;
    }

    private String resolveConfigPath(String inputPath) {
        if (isSupportedConfigLeaf(inputPath)) {
            return inputPath;
        }
        if (isSupportedConfigLeaf("tiers." + inputPath)) {
            return "tiers." + inputPath;
        }
        if (inputPath.startsWith("tiers.") && isSupportedConfigLeaf(inputPath.substring("tiers.".length()))) {
            return inputPath;
        }
        return inputPath;
    }

    private Object parseValueForPath(String path, String raw) {
        if ("kill-penalty.exempt-worlds".equals(path) || "effects.fake-announcement.fake-names".equals(path)) {
            return parseStringList(raw);
        }
        return parseValue(raw);
    }

    private String formatRawValueForPath(String path, String raw) {
        if ("kill-penalty.exempt-worlds".equals(path) || "effects.fake-announcement.fake-names".equals(path)) {
            List<String> list = parseStringList(raw);
            return "[" + String.join(", ", list) + "]";
        }
        return raw;
    }

    static List<String> parseStringList(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = trimmed.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String item = part.trim();
            if ((item.startsWith("'") && item.endsWith("'")) || (item.startsWith("\"") && item.endsWith("\""))) {
                item = item.substring(1, item.length() - 1).trim();
            }
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private Object parseValue(String raw) {
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(trimmed)) return Boolean.FALSE;

        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }

        if (trimmed.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
            }
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

    private static String resolveBundledVersion() {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            if (in != null) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                String v = yaml.getString("version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "1.0";
    }

    private static Set<String> createSupportedConfigLeaves() {
        Set<String> set = new HashSet<>();
        set.add("language");
        set.add("chat-prefix");

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

        set.add("decay.enabled");
        set.add("decay.half-life");
        set.add("decay.floor");
        set.add("decay.cache-ttl");

        set.add("psychosis.window");
        set.add("psychosis.medium-threshold");
        set.add("psychosis.high-threshold");
        set.add("psychosis.extreme-threshold");

        set.add("honor.cost");
        set.add("honor.multiplier-window");
        set.add("honor.cap-window");
        set.add("honor.cooldown-per-pair");
        set.add("honor.max-per-target");

        set.add("permissions.show");
        set.add("permissions.show-others");
        set.add("permissions.give-reputation");
        set.add("permissions.take-reputation");
        set.add("permissions.view-reputation");
        set.add("permissions.admin-adjust");
        set.add("permissions.admin-config");
        set.add("permissions.duel");
        set.add("permissions.effects");
        set.add("permissions.version");
        set.add("permissions.admin-update");
        set.add("permissions.admin-import");

        set.add("duel.challenge-timeout");
        set.add("duel.disconnect.combat-log-window");
        set.add("duel.disconnect.reconnect-grace-period");
        set.add("duel.disconnect.action");

        set.add("effects.threshold");
        set.add("effects.check-interval");
        set.add("effects.silverfish.cooldown");
        set.add("effects.silverfish.session-cap");
        set.add("effects.silverfish.duration-ticks");
        set.add("effects.whisper.cooldown");
        set.add("effects.whisper.session-cap");
        set.add("effects.creeper.cooldown");
        set.add("effects.creeper.session-cap");
        set.add("effects.fake-announcement.cooldown");
        set.add("effects.fake-announcement.session-cap");
        set.add("effects.fake-announcement.fake-names");

        set.add("legacy-import.trust-name-lookup");

        set.add("update.check-on-startup");
        set.add("update.auto-download");
        set.add("update.repository");
        set.add("update.channel");
        set.add("update.api-url");
        set.add("update.max-download-bytes");

        set.add("kill-penalty.delta");
        set.add("kill-penalty.pair-cooldown");
        set.add("kill-penalty.cap-window");
        set.add("kill-penalty.max-loss");
        set.add("kill-penalty.exempt-worlds");

        set.add("sounds.creeper-fuse.key");
        set.add("sounds.creeper-fuse.volume");
        set.add("sounds.creeper-fuse.pitch");
        set.add("sounds.creeper-fuse.category");
        set.add("sounds.creeper-fuse.delay");
        set.add("history.reveal-cost");

        return Collections.unmodifiableSet(set);
    }

}
