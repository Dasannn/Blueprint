package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private void retireEffectsKeys() {
        removeObsoleteKeys(configFile, List.of("effects.threshold",
                "effects.fake-announcement.fake-names", "permissions.effects",
                "effects.whisper.cooldown", "effects.whisper.session-cap",
                "effects.fake-announcement.cooldown", "effects.fake-announcement.session-cap"));
        for (String language : List.of("en", "es")) {
            removeObsoleteKeys(new File(configFile.getParentFile(), "messages_" + language + ".yml"),
                    List.of("effects.opt-out-enabled", "effects.opt-out-disabled"));
        }
    }

    private void removeObsoleteKeys(File file, List<String> keys) {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : keys) {
            if (yaml.contains(key)) {
                try {
                    YamlFileUpdater.removeLeafAndSave(file, key);
                } catch (IOException e) {
                    throw new ConfigValidationException(key, "Cannot retire obsolete key: " + e.getMessage());
                }
            }
        }
    }

    private void adoptChatExtents(YamlConfiguration before) {
        String oldKey = "psychosis.chat.extent";
        if (!before.contains(oldKey)) return;
        Object raw = before.get(oldKey);
        int value;
        try { value = Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException error) { throw new ConfigValidationException(oldKey, "Must be an integer"); }
        if (value < 1 || value > 50) throw new ConfigValidationException(oldKey, "Must be between 1 and 50 percent");
        try {
            if (value != 20) for (String level : List.of("medium", "high", "extreme")) {
                String key = "psychosis.chat." + level + "-extent";
                if (!before.contains(key)) YamlFileUpdater.updateLeafAndSave(configFile, key, Integer.toString(value));
            }
            YamlFileUpdater.removeLeafAndSave(configFile, oldKey);
        } catch (IOException error) { throw new ConfigValidationException(oldKey, error.getMessage()); }
    }

    private void adoptEpisodeIntervals(YamlConfiguration beforeMerge) {
        for (String level : List.of("medium", "high", "extreme")) {
            String path = "effects.episodes." + level + ".interval-ticks";
            String legacy = "effects.quiet-interval." + level;
            if (!beforeMerge.contains(path) && beforeMerge.contains(legacy)) {
                // Preserve the previous guaranteed floor, including its scheduler check interval.
                EffectsConfigSection previous = EffectsConfigSection.load(beforeMerge);
                long millis = previous.quietInterval(com.dasannn.socialblueprint.domain.PsychosisLevel.valueOf(level.toUpperCase(java.util.Locale.ROOT))).toMillis();
                long ticks = millis / 50L + (millis % 50L == 0 ? 0 : 1);
                try { YamlFileUpdater.updateLeafAndSave(configFile, path, Long.toString(ticks)); }
                catch (IOException error) { throw new ConfigValidationException(path, "Cannot adopt legacy cadence: " + error.getMessage()); }
            }
        }
    }

    private Map<String, YamlConfiguration> loadMessagesBeforeMerge() {
        Map<String, YamlConfiguration> before = new java.util.HashMap<>();
        for (String language : List.of("en", "es")) {
            File file = new File(configFile.getParentFile(), "messages_" + language + ".yml");
            if (file.exists()) before.put(language, YamlConfiguration.loadConfiguration(file));
        }
        return before;
    }

    // After the merge, not before: the updater only rewrites leaves that
    // exist, and an older messages file has no effects.fake-connection or
    // effects.private-chat until the merge adds the defaults.
    private void adoptPrivateTextMessages(Map<String, YamlConfiguration> beforeMerge) {
        for (Map.Entry<String, YamlConfiguration> entry : beforeMerge.entrySet()) {
            File file = new File(configFile.getParentFile(), "messages_" + entry.getKey() + ".yml");
            YamlConfiguration before = entry.getValue();
            try {
                for (String[] alias : List.of(new String[]{"effects.fake-connection.join", "effects.fake-join"},
                        new String[]{"effects.fake-connection.leave", "effects.fake-leave"})) {
                    if (!before.contains(alias[0]) && before.isString(alias[1]))
                        YamlFileUpdater.updateLeafAndSave(file, alias[0], "'" + before.getString(alias[1]).replace("'", "''") + "'");
                }
                if (!before.contains("effects.private-chat.lines")) {
                    List<String> lines = new ArrayList<>();
                    for (int i = 1; i <= 3; i++) if (before.isString("effects.whisper-" + i))
                        lines.add(before.getString("effects.whisper-" + i));
                    if (!lines.isEmpty()) YamlFileUpdater.updateLeafAndSave(file, "effects.private-chat.lines",
                            "[" + String.join(", ", lines.stream().map(s -> "'" + s.replace("'", "''") + "'").toList()) + "]");
                }
            } catch (IOException error) { throw new ConfigValidationException("effects.private-chat", error.getMessage()); }
        }
    }

    private void adoptPrivateTextLimits(YamlConfiguration before) {
        for (String[] alias : List.of(new String[]{"private-chat", "whisper"}, new String[]{"fake-connection", "fake-announcement"})) {
            String target = "effects." + alias[0];
            String legacy = "effects." + alias[1];
            try {
                if (!before.contains(target + ".cooldown-ticks") && before.contains(legacy + ".cooldown")) {
                    long millis = DurationParser.parseNonNegative(before.getString(legacy + ".cooldown"), legacy + ".cooldown").toMillis();
                    YamlFileUpdater.updateLeafAndSave(configFile, target + ".cooldown-ticks", Long.toString(Math.max(1, millis / 50 + (millis % 50 == 0 ? 0 : 1))));
                }
                if (!before.contains(target + ".session-cap") && before.contains(legacy + ".session-cap"))
                    YamlFileUpdater.updateLeafAndSave(configFile, target + ".session-cap", Integer.toString(before.getInt(legacy + ".session-cap")));
            } catch (IOException error) { throw new ConfigValidationException(target, error.getMessage()); }
        }
    }

    public CompletableFuture<RuntimeSnapshot> reloadAsync() {
        return CompletableFuture.supplyAsync(this::reload, ioExecutor);
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
            YamlConfiguration beforeMerge = YamlConfiguration.loadConfiguration(configFile);
            Map<String, YamlConfiguration> messagesBeforeMerge = loadMessagesBeforeMerge();
            ConfigMerger.mergeMissingDefaults(configFile, dataFolder, versionSupplier.get(), logger);
            adoptPrivateTextMessages(messagesBeforeMerge);
            adoptEpisodeIntervals(beforeMerge);
            adoptPrivateTextLimits(beforeMerge);
            adoptChatExtents(beforeMerge);
            retireEffectsKeys();

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            PluginConfig newConfig = PluginConfig.load(yaml);
            MessagesSnapshot newMessages = MessageRegistry.loadMessagesSnapshot(dataFolder, newConfig.language(), logger);
            Map<String, String> leafValues = extractLeafValues(yaml);
            RuntimeSnapshot newSnapshot;
            try { newSnapshot = new RuntimeSnapshot(newConfig, newMessages, leafValues); }
            catch (ConfigValidationException error) { logger.warning(error.getMessage()); throw error; }
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
            String val = snapshot.getLeaf(resolved);
            if (val != null) {
                return val;
            }
            val = getLeafFromConfig(snapshot.config(), resolved);
            if (val != null) {
                return val;
            }
            return "";
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
                try { CatalogueLines.validateSnapshot(current.messages(), newConfig.effects().presentation().maxVisibleLength()); }
                catch (ConfigValidationException error) { logger.warning(error.getMessage()); throw error; }

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

                Map<String, String> leafValues = extractLeafValues(yaml);
                RuntimeSnapshot newSnapshot = new RuntimeSnapshot(newConfig, messages, leafValues);
                snapshotRef.set(newSnapshot);
                notifySnapshotListeners(newSnapshot);
                return newSnapshot;
            }

            // Handle message leaf edit (SB-062, constitution §2.8)
            if (current.messages().isKnownKey(path)) {
                if (rawValue.isBlank()) {
                    throw new ConfigValidationException(path, "Message translation must not be blank");
                }
                String messageValue;
                try {
                    int max = current.config().effects().presentation().maxVisibleLength();
                    boolean screenList = path.equals(ScreenLines.KEY) || path.equals("effects.sign.lines");
                    messageValue = CatalogueLines.LISTS.contains(path) ? CatalogueLines.editValue(path, rawValue, max)
                            : screenList ? ScreenLines.editValue(rawValue, path) : rawValue;
                    if (CatalogueLines.TEMPLATES.contains(path)) CatalogueLines.validateLine(path, -1, rawValue, Map.of(), 160, false);
                    if (!screenList && !CatalogueLines.LISTS.contains(path)) ColorParser.validate(rawValue, path);
                    if (path.equals("effects.victim-ghost.label")) ScreenLines.validateGhostLabel(rawValue);
                } catch (ConfigValidationException error) { logger.warning(error.getMessage()); throw error; }

                File dataFolder = configFile.getParentFile();
                String activeLang = current.config().language();
                File messageFile = new File(dataFolder, "messages_" + activeLang + ".yml");
                if (!messageFile.exists()) {
                    // Ensure disk file exists before updating
                    MessageRegistry.loadMessagesSnapshot(dataFolder, activeLang, logger);
                }

                // Persist atomically to the active language file
                try {
                    YamlFileUpdater.updateLeafAndSave(messageFile, path, messageValue);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to persist updated message to disk: " + e.getMessage(), e);
                }

                // Reload messages and publish updated snapshot
                MessagesSnapshot updatedMessages = MessageRegistry.loadMessagesSnapshot(dataFolder, activeLang, logger);
                RuntimeSnapshot newSnapshot = new RuntimeSnapshot(current.config(), updatedMessages, current.leafValues());
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
        if ("sounds.serenity-clean".equals(path)) {
            if (raw.contains("\n") || raw.contains("\r"))
                throw new ConfigValidationException(path, "Use an inline YAML list of sound layers");
            YamlConfiguration parsed = YamlConfiguration.loadConfiguration(new java.io.StringReader("value: " + raw));
            if (!parsed.isList("value") || parsed.getKeys(false).size() != 1)
                throw new ConfigValidationException(path, "Must be a YAML list of sound layers");
            List<?> layers = parsed.getList("value");
            if (layers.stream().anyMatch(layer -> !(layer instanceof Map<?, ?>)))
                throw new ConfigValidationException(path, "Each sound layer must be a mapping");
            return layers;
        }
        if (path.startsWith("effects.episodes.") && !path.startsWith("effects.episodes.duration-scale.")) {
            try { return Long.parseLong(raw.trim()); }
            catch (NumberFormatException error) { throw new ConfigValidationException(path, "Expected integer ticks"); }
        }
        if ("honor.multipliers".equals(path)) {
            return parseDoubleList(raw);
        }
        if ("kill-penalty.exempt-worlds".equals(path) || "effects.silverfish.mobs".equals(path)) {
            return parseStringList(raw);
        }
        return parseValue(raw);
    }

    private String formatRawValueForPath(String path, String raw) {
        if ("honor.multipliers".equals(path)) {
            List<Double> list = parseDoubleList(raw);
            return list.toString();
        }
        if ("kill-penalty.exempt-worlds".equals(path) || "effects.silverfish.mobs".equals(path)) {
            List<String> list = parseStringList(raw);
            return "[" + String.join(", ", list) + "]";
        }
        return raw;
    }

    static List<Double> parseDoubleList(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = trimmed.split(",");
        List<Double> result = new ArrayList<>();
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(Double.parseDouble(item));
            }
        }
        return result;
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
        set.add("psychosis.chat.medium-rate");
        set.add("psychosis.chat.high-rate");
        set.add("psychosis.chat.extreme-rate");
        set.add("psychosis.chat.medium-extent");
        set.add("psychosis.chat.high-extent");
        set.add("psychosis.chat.extreme-extent");
        set.add("psychosis.chat.min-letters");
        set.add("psychosis.serenity.ceiling");
        set.add("psychosis.serenity.active-hours-to-ceiling");
        set.add("psychosis.serenity.idle-timeout-seconds");

        set.add("honor.cost");
        set.add("honor.multipliers");
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
        set.add("permissions.version");
        set.add("permissions.admin-update");
        set.add("permissions.admin-import");

        set.add("duel.challenge-timeout");
        set.add("duel.disconnect.combat-log-window");
        set.add("duel.disconnect.reconnect-grace-period");
        set.add("duel.disconnect.action");
        set.add("duel.attack-context-window");

        for (String id : List.of("sky", "particles", "screen-flash", "source-less-sounds", "block-change", "sign", "hurt-flash", "victim-ghost",
                "advancement-toast", "boss-bar", "false-death", "private-chat", "fake-connection")) {
            for (String key : List.of("enabled", "minimum-level", "cooldown-ticks", "session-cap"))
                set.add("effects." + id + "." + key);
        }
        for (String key : List.of("sky.mode", "sky.duration-ticks", "particles.type", "particles.placement",
                "particles.count", "particles.radius-blocks", "particles.duration-ticks", "screen-flash.channel",
                "screen-flash.fade-in-ticks", "screen-flash.duration-ticks", "screen-flash.fade-out-ticks",
                "source-less-sounds.sound-slot", "source-less-sounds.offset.forward-blocks",
                "source-less-sounds.offset.right-blocks", "source-less-sounds.offset.up-blocks", "source-less-sounds.playback-ticks"))
            set.add("effects." + key);
        for (String level : List.of("medium", "high", "extreme")) set.add("effects.episodes." + level + ".interval-ticks");
        set.add("effects.episodes.quiet-ticks");
        for (String level : List.of("medium", "high", "extreme")) set.add("effects.episodes.duration-scale." + level);
        for (String key : List.of("mobs", "duration-ticks", "distance-blocks")) set.add("effects.silverfish." + key);
        for (String id : List.of("block-change", "sign", "victim-ghost")) {
            set.add("effects." + id + ".range-blocks");
            set.add("effects." + id + ".duration-ticks");
        }
        set.add("effects.block-change.block-data");
        set.add("effects.sign.block-data");
        set.add("effects.hurt-flash.sound-slot");
        set.add("effects.hurt-flash.playback-ticks");
        for (String key : List.of("advancement-toast.icon", "advancement-toast.duration-ticks", "boss-bar.colour",
                "boss-bar.style", "boss-bar.progress", "boss-bar.duration-ticks", "false-death.range-blocks", "private-chat.max-visible-length"))
            set.add("effects." + key);
        set.add("effects.check-interval");
        set.addAll(SerenityEffectsConfig.defaults().leafValues().keySet());
        set.add("effects.quiet-interval.medium");
        set.add("effects.quiet-interval.high");
        set.add("effects.quiet-interval.extreme");
        set.add("effects.max-episode-ticks");
        set.add("effects.silverfish.cooldown");
        set.add("effects.silverfish.session-cap");
        set.add("effects.creeper.cooldown");
        set.add("effects.creeper.session-cap");

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
        set.add("sounds.serenity-clean");
        set.add("history.reveal-cost");

        return Collections.unmodifiableSet(set);
    }

    static Map<String, String> extractLeafValues(ConfigurationSection root) {
        if (root == null) {
            return Collections.emptyMap();
        }
        Map<String, String> leaves = new HashMap<>();
        for (String leaf : SUPPORTED_CONFIG_LEAVES) {
            if (root.contains(leaf)) {
                Object val = root.get(leaf);
                if (val != null) {
                    leaves.put(leaf, val.toString());
                }
            }
        }
        return Collections.unmodifiableMap(leaves);
    }

    static String getLeafFromConfig(PluginConfig config, String path) {
        if (config == null || path == null) return null;
        if (path.startsWith("effects.serenity.")) return config.effects().serenity().leafValues().get(path);
        if ("language".equals(path)) return config.language();
        if ("chat-prefix".equals(path)) return config.chatPrefix();

        if (path.startsWith("tiers.")) {
            String[] parts = path.split("\\.");
            if (parts.length == 3 && config.tiers() != null) {
                for (Tier tier : Tier.values()) {
                    if (tier.configKey().equalsIgnoreCase(parts[1])) {
                        TierConfig tc = config.tiers().get(tier);
                        if (tc != null) {
                            if ("prefix".equals(parts[2])) return tc.prefix();
                            if ("threshold".equals(parts[2])) return String.valueOf(tc.threshold());
                        }
                    }
                }
            }
        }

        if ("confidence.half-life".equals(path) && config.confidence() != null) return formatDuration(config.confidence().halfLife());
        if ("confidence.low-threshold".equals(path) && config.confidence() != null) return String.valueOf(config.confidence().lowThreshold());
        if ("confidence.established-threshold".equals(path) && config.confidence() != null) return String.valueOf(config.confidence().establishedThreshold());
        if ("confidence.high-threshold".equals(path) && config.confidence() != null) return String.valueOf(config.confidence().highThreshold());

        if ("decay.enabled".equals(path) && config.decay() != null) return String.valueOf(config.decay().enabled());
        if ("decay.half-life".equals(path) && config.decay() != null) return formatDuration(config.decay().halfLife());
        if ("decay.floor".equals(path) && config.decay() != null) return String.valueOf(config.decay().floor());
        if ("decay.cache-ttl".equals(path) && config.decay() != null) return formatDuration(config.decay().cacheTtl());

        if ("psychosis.window".equals(path) && config.psychosis() != null) return formatDuration(config.psychosis().window());
        if ("psychosis.medium-threshold".equals(path) && config.psychosis() != null) return String.valueOf(config.psychosis().mediumThreshold());
        if ("psychosis.high-threshold".equals(path) && config.psychosis() != null) return String.valueOf(config.psychosis().highThreshold());
        if ("psychosis.extreme-threshold".equals(path) && config.psychosis() != null) return String.valueOf(config.psychosis().extremeThreshold());
        if ("psychosis.chat.medium-rate".equals(path)) return String.valueOf(config.psychosis().chat().mediumRate());
        if ("psychosis.chat.high-rate".equals(path)) return String.valueOf(config.psychosis().chat().highRate());
        if ("psychosis.chat.extreme-rate".equals(path)) return String.valueOf(config.psychosis().chat().extremeRate());
        if ("psychosis.chat.min-letters".equals(path)) return String.valueOf(config.psychosis().chat().minLetters());
        if ("psychosis.chat.medium-extent".equals(path)) return String.valueOf(config.psychosis().chat().mediumExtent());
        if ("psychosis.chat.high-extent".equals(path)) return String.valueOf(config.psychosis().chat().highExtent());
        if ("psychosis.chat.extreme-extent".equals(path)) return String.valueOf(config.psychosis().chat().extremeExtent());
        if ("psychosis.serenity.ceiling".equals(path)) return String.valueOf(config.psychosis().serenity().ceiling());
        if ("psychosis.serenity.active-hours-to-ceiling".equals(path)) return String.valueOf(config.psychosis().serenity().activeHoursToCeiling());
        if ("psychosis.serenity.idle-timeout-seconds".equals(path)) return String.valueOf(config.psychosis().serenity().idleTimeoutSeconds());

        if ("honor.cost".equals(path) && config.honor() != null) return String.valueOf(config.honor().cost());
        if ("honor.multipliers".equals(path) && config.honor() != null) return config.honor().multipliers().toString();
        if ("honor.multiplier-window".equals(path) && config.honor() != null) return formatDuration(config.honor().multiplierWindow());
        if ("honor.cap-window".equals(path) && config.honor() != null) return formatDuration(config.honor().capWindow());
        if ("honor.cooldown-per-pair".equals(path) && config.honor() != null) return formatDuration(config.honor().cooldownPerPair());
        if ("honor.max-per-target".equals(path) && config.honor() != null) return String.valueOf(config.honor().maxPerTarget());

        if (config.permissions() != null && path.startsWith("permissions.")) {
            // One lookup, not an accessor per action: the record holds a map and
            // twelve hand-written getters would silently fall behind it.
            String action = path.substring("permissions.".length());
            if (PermissionsConfig.REQUIRED_ACTIONS.contains(action)
                    || config.permissions().nodes().containsKey(action)) {
                return config.permissions().node(action);
            }
        }

        if (config.duel() != null) {
            if ("duel.challenge-timeout".equals(path)) return formatDuration(config.duel().challengeTimeout());
            if ("duel.disconnect.combat-log-window".equals(path)) return formatDuration(config.duel().disconnect().combatLogWindow());
            if ("duel.disconnect.reconnect-grace-period".equals(path)) return formatDuration(config.duel().disconnect().reconnectGracePeriod());
            if ("duel.disconnect.action".equals(path)) return config.duel().disconnect().action();
            if ("duel.attack-context-window".equals(path)) return formatDuration(config.duel().attackContextWindow());
        }

        if (config.effects() != null) {
            if (path.startsWith("effects.episodes.duration-scale.")) {
                var scale = config.effects().presentation().durationScale();
                return switch (path.substring("effects.episodes.duration-scale.".length())) {
                    case "medium" -> String.valueOf(scale.medium());
                    case "high" -> String.valueOf(scale.high());
                    case "extreme" -> String.valueOf(scale.extreme());
                    default -> null;
                };
            }
            if ("effects.silverfish.mobs".equals(path)) return config.effects().presentation().phantom().mobs().toString();
            if ("effects.silverfish.duration-ticks".equals(path)) return String.valueOf(config.effects().presentation().phantom().durationTicks());
            if ("effects.silverfish.distance-blocks".equals(path)) return String.valueOf(config.effects().presentation().phantom().distance());

            if ("effects.quiet-interval.medium".equals(path)) return formatDuration(config.effects().mediumQuietInterval());
            if ("effects.quiet-interval.high".equals(path)) return formatDuration(config.effects().highQuietInterval());
            if ("effects.quiet-interval.extreme".equals(path)) return formatDuration(config.effects().extremeQuietInterval());
            if ("effects.max-episode-ticks".equals(path)) return String.valueOf(config.effects().maxEpisodeTicks());
            if ("effects.check-interval".equals(path)) return formatDuration(config.effects().checkInterval());
            if ("effects.silverfish.cooldown".equals(path)) return formatDuration(config.effects().silverfish().cooldown());
            if ("effects.silverfish.session-cap".equals(path)) return String.valueOf(config.effects().silverfish().sessionCap());
            if ("effects.creeper.cooldown".equals(path)) return formatDuration(config.effects().creeper().cooldown());
            if ("effects.creeper.session-cap".equals(path)) return String.valueOf(config.effects().creeper().sessionCap());
        }

        if (config.legacyImport() != null && "legacy-import.trust-name-lookup".equals(path)) {
            return String.valueOf(config.legacyImport().trustNameLookup());
        }

        if (config.update() != null) {
            if ("update.check-on-startup".equals(path)) return String.valueOf(config.update().checkOnStartup());
            if ("update.auto-download".equals(path)) return String.valueOf(config.update().autoDownload());
            if ("update.repository".equals(path)) return config.update().repository();
            if ("update.channel".equals(path)) return config.update().channel();
            if ("update.api-url".equals(path)) return config.update().apiUrl();
            if ("update.max-download-bytes".equals(path)) return String.valueOf(config.update().maxDownloadBytes());
        }

        if (config.killPenalty() != null) {
            if ("kill-penalty.delta".equals(path)) return String.valueOf(config.killPenalty().delta());
            if ("kill-penalty.pair-cooldown".equals(path)) return formatDuration(config.killPenalty().pairCooldown());
            if ("kill-penalty.cap-window".equals(path)) return formatDuration(config.killPenalty().capWindow());
            if ("kill-penalty.max-loss".equals(path)) return String.valueOf(config.killPenalty().maxLoss());
            if ("kill-penalty.exempt-worlds".equals(path)) return config.killPenalty().exemptWorlds().toString();
        }

        if (config.history() != null && "history.reveal-cost".equals(path)) {
            return String.valueOf(config.history().revealCost());
        }

        if (path.startsWith("sounds.")) {
            String[] parts = path.split("\\.");
            if (parts.length == 3 && config.sounds() != null) {
                SoundSlotConfig slot = config.sounds().get(parts[1]);
                if (slot != null) {
                    if ("key".equals(parts[2])) return slot.key();
                    if ("volume".equals(parts[2])) return String.valueOf(slot.volume());
                    if ("pitch".equals(parts[2])) return String.valueOf(slot.pitch());
                    if ("category".equals(parts[2])) return slot.category().name().toLowerCase(Locale.ROOT);
                    if ("delay".equals(parts[2])) return String.valueOf(slot.layers().isEmpty() ? 0L : slot.layers().getFirst().delay());
                }
            }
        }
        return null;
    }

    private static String formatDuration(java.time.Duration d) {
        if (d == null) return "0s";
        long seconds = d.getSeconds();
        if (seconds % 86400 == 0 && seconds > 0) return (seconds / 86400) + "d";
        if (seconds % 3600 == 0 && seconds > 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0 && seconds > 0) return (seconds / 60) + "m";
        return seconds + "s";
    }
}
