package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Message resolution and localization service per T-032, T-032a, T-032b, and T-032d.
 * Uses an immutable {@link RuntimeSnapshot} published through a single atomic reference.
 * The chat prefix is rendered directly from the authoritative configuration snapshot (SB-062).
 * Logs a warning naming missing keys ONCE.
 * A raw key must never reach a player (T-032b).
 */
public class MessageRegistry {

    private final File dataFolder;
    private final Logger logger;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicReference<RuntimeSnapshot> snapshotRef = new AtomicReference<>();

    public MessageRegistry(File dataFolder, String language, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger != null ? logger : Logger.getLogger(MessageRegistry.class.getName());

        String initialLang = (language != null && !language.isBlank())
                ? language.trim().toLowerCase(Locale.ROOT)
                : "en";
        PluginConfig initialConfig = loadBundledConfig(initialLang);
        MessagesSnapshot initialMessages = loadMessagesSnapshot(dataFolder, initialLang, this.logger);
        this.snapshotRef.set(new RuntimeSnapshot(initialConfig, initialMessages));
    }

    public AtomicReference<RuntimeSnapshot> snapshotReference() {
        return snapshotRef;
    }

    public RuntimeSnapshot snapshot() {
        return snapshotRef.get();
    }

    /**
     * Changes the active language, reloading message maps from disk/bundled files
     * and atomically updating the runtime snapshot.
     */
    public synchronized void setLanguage(String language) {
        String newLang = (language != null && !language.isBlank())
                ? language.trim().toLowerCase(Locale.ROOT)
                : "en";
        RuntimeSnapshot current = snapshotRef.get();
        PluginConfig cfg = current != null ? current.config() : loadBundledConfig(newLang);
        MessagesSnapshot newMessages = loadMessagesSnapshot(dataFolder, newLang, logger);
        snapshotRef.set(new RuntimeSnapshot(cfg, newMessages));
    }

    public String activeLanguage() {
        RuntimeSnapshot snap = snapshotRef.get();
        return snap != null ? snap.messages().activeLanguage() : "en";
    }

    /**
     * Returns the raw translated string for a given key, applying fallback if missing.
     * Logs a warning naming the key ONCE on fallback.
     * Never returns the raw key if completely missing (T-032b).
     */
    public String getRaw(String key) {
        RuntimeSnapshot snap = snapshotRef.get();
        if (snap == null) {
            return "";
        }
        return snap.messages().resolveRaw(key, warnedKeys, logger);
    }

    /**
     * Renders a message key as an Adventure Component with legacy '&' and hex color formatting.
     */
    public Component render(String key) {
        return render(key, Collections.emptyMap());
    }

    /**
     * Renders a message key with placeholder replacements as an Adventure Component.
     * Fixed template spans are parsed for formatting while placeholder values are appended
     * as literal {@link Component#text(String)}, preventing player input from recolouring messages.
     */
    public Component render(String key, Map<String, String> placeholders) {
        String template = getRaw(key);
        if (template.isEmpty()) {
            return Component.empty();
        }
        return ColorParser.renderTemplate(template, placeholders);
    }

    /**
     * Renders a message key prefixed by the authoritative chat prefix from configuration.
     */
    public Component renderWithPrefix(String key) {
        return renderWithPrefix(key, Collections.emptyMap());
    }

    /**
     * Renders a message key with placeholder replacements prefixed by the authoritative
     * chat prefix from the current runtime snapshot.
     */
    public Component renderWithPrefix(String key, Map<String, String> placeholders) {
        RuntimeSnapshot snap = snapshotRef.get();
        Component prefix = snap != null ? snap.chatPrefixComponent() : Component.empty();
        Component message = render(key, placeholders);
        return prefix.append(message);
    }

    /**
     * Returns the localized display name for a tier per SB-070i and T-032d.
     */
    public String tierName(Tier tier) {
        Objects.requireNonNull(tier, "Tier must not be null");
        return getRaw("tiers." + tier.configKey());
    }

    /**
     * Loads messages directly from in-memory maps (primarily for testing).
     */
    public static MessageRegistry fromMaps(Map<String, String> active, Map<String, String> fallback, String lang, Logger logger) {
        MessageRegistry registry = new MessageRegistry(null, lang, logger);
        String activeLang = (lang != null && !lang.isBlank()) ? lang.trim().toLowerCase(Locale.ROOT) : "en";
        String fallbackLang = "es".equalsIgnoreCase(activeLang) ? "en" : "es";
        MessagesSnapshot ms = new MessagesSnapshot(activeLang, fallbackLang, active, fallback, Collections.emptyMap(), Collections.emptyMap());
        PluginConfig defaultCfg = loadBundledConfig(activeLang);
        registry.snapshotRef.set(new RuntimeSnapshot(defaultCfg, ms));
        return registry;
    }

    public static MessagesSnapshot loadMessagesSnapshot(File dataFolder, String language, Logger logger) {
        String activeLang = (language != null && !language.isBlank())
                ? language.trim().toLowerCase(Locale.ROOT)
                : "en";
        String fallbackLang = "es".equalsIgnoreCase(activeLang) ? "en" : "es";

        Map<String, String> bundledActive = loadFromJar(activeLang, logger);
        Map<String, String> bundledFallback = loadFromJar(fallbackLang, logger);

        Map<String, String> active = loadFromDiskOrJar(dataFolder, activeLang, bundledActive, logger);
        Map<String, String> fallback = loadFromDiskOrJar(dataFolder, fallbackLang, bundledFallback, logger);

        return new MessagesSnapshot(activeLang, fallbackLang, active, fallback, bundledActive, bundledFallback);
    }

    private static Map<String, String> loadFromDiskOrJar(File dataFolder, String lang, Map<String, String> bundled, Logger logger) {
        if (dataFolder == null) {
            return bundled != null ? bundled : Collections.emptyMap();
        }

        File file = new File(dataFolder, "messages_" + lang + ".yml");
        if (!file.exists()) {
            saveBundledResource(lang, file, logger);
        }

        if (file.exists()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                return flattenKeys(yaml);
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[SocialBlueprint] Failed to load messages_" + lang + ".yml from disk: " + e.getMessage());
                }
            }
        }

        return bundled != null ? bundled : Collections.emptyMap();
    }

    private static void saveBundledResource(String lang, File targetFile, Logger logger) {
        String resourcePath = "messages_" + lang + ".yml";
        try (InputStream in = MessageRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            if (targetFile.getParentFile() != null) {
                targetFile.getParentFile().mkdirs();
            }
            java.nio.file.Files.copy(in, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[SocialBlueprint] Could not extract default " + resourcePath + ": " + e.getMessage());
            }
        }
    }

    private static Map<String, String> loadFromJar(String lang, Logger logger) {
        String resourcePath = "messages_" + lang + ".yml";
        try (InputStream in = MessageRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                return flattenKeys(yaml);
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[SocialBlueprint] Could not read bundled " + resourcePath + ": " + e.getMessage());
            }
            return Collections.emptyMap();
        }
    }

    private static PluginConfig loadBundledConfig(String language) {
        try (InputStream in = MessageRegistry.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                    return PluginConfig.load(yaml);
                }
            }
        } catch (Exception ignored) {
        }
        // Minimal fallback config if jar resource fails
        return new PluginConfig(
                language,
                "&8[&bSocialBlueprint&8]&r ",
                null, null, null, null, null
        );
    }

    public static Map<String, String> flattenKeys(ConfigurationSection section) {
        Map<String, String> map = new HashMap<>();
        flattenRecursive(section, "", map);
        return map;
    }

    private static void flattenRecursive(ConfigurationSection section, String prefix, Map<String, String> map) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                flattenRecursive(section.getConfigurationSection(key), path, map);
            } else {
                map.put(path, section.getString(key, ""));
            }
        }
    }
}
