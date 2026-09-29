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
import java.util.logging.Logger;

/**
 * Message resolution and localization service per T-032, T-032a, T-032b, and T-032d.
 * Loads active language with fallback to the alternate language.
 * Logs a warning naming missing keys ONCE.
 * A raw key must never reach a player (T-032b).
 */
public class MessageRegistry {

    private final File dataFolder;
    private final Logger logger;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    private volatile String activeLanguage;
    private volatile Map<String, String> activeMessages;
    private volatile Map<String, String> fallbackMessages;
    private volatile Map<String, String> bundledActiveMessages;
    private volatile Map<String, String> bundledFallbackMessages;

    public MessageRegistry(File dataFolder, String language, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger != null ? logger : Logger.getLogger(MessageRegistry.class.getName());
        setLanguage(language);
    }

    /**
     * Changes the active language, reloading message maps from disk/bundled files.
     */
    public synchronized void setLanguage(String language) {
        this.activeLanguage = (language != null && !language.isBlank())
                ? language.trim().toLowerCase(Locale.ROOT)
                : "en";
        String fallbackLang = "es".equalsIgnoreCase(this.activeLanguage) ? "en" : "es";

        this.bundledActiveMessages = loadFromJar(this.activeLanguage);
        this.bundledFallbackMessages = loadFromJar(fallbackLang);

        this.activeMessages = loadFromDiskOrJar(this.activeLanguage, bundledActiveMessages);
        this.fallbackMessages = loadFromDiskOrJar(fallbackLang, bundledFallbackMessages);
    }

    public String activeLanguage() {
        return activeLanguage;
    }

    /**
     * Returns the raw translated string for a given key, applying fallback if missing.
     * Logs a warning naming the key ONCE on fallback.
     * Never returns the raw key if completely missing (T-032b).
     */
    public String getRaw(String key) {
        Objects.requireNonNull(key, "Message key must not be null");

        // 1. Try active language from disk/memory
        String val = activeMessages.get(key);
        if (val != null && !val.isBlank()) {
            return val;
        }

        // 2. Try fallback language from disk/memory
        String fallbackLang = "es".equalsIgnoreCase(activeLanguage) ? "en" : "es";
        String fallbackVal = fallbackMessages.get(key);
        if (fallbackVal != null && !fallbackVal.isBlank()) {
            if (warnedKeys.add(key)) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in language '" + activeLanguage + "'; falling back to '" + fallbackLang + "'.");
            }
            return fallbackVal;
        }

        // 3. Try bundled active language from jar
        String bundledVal = bundledActiveMessages.get(key);
        if (bundledVal != null && !bundledVal.isBlank()) {
            if (warnedKeys.add(key)) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in disk file; falling back to bundled jar default for '" + activeLanguage + "'.");
            }
            return bundledVal;
        }

        // 4. Try bundled fallback language from jar
        String bundledFallbackVal = bundledFallbackMessages.get(key);
        if (bundledFallbackVal != null && !bundledFallbackVal.isBlank()) {
            if (warnedKeys.add(key)) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in disk file; falling back to bundled jar fallback for '" + fallbackLang + "'.");
            }
            return bundledFallbackVal;
        }

        // 5. Completely missing - never show raw key to player (T-032b)
        if (warnedKeys.add(key)) {
            logger.severe("[SocialBlueprint] Translation key '" + key + "' not found in any message file!");
        }
        return "";
    }

    /**
     * Renders a message key as an Adventure Component with legacy '&' and hex color formatting.
     */
    public Component render(String key) {
        return render(key, Collections.emptyMap());
    }

    /**
     * Renders a message key with placeholder replacements as an Adventure Component.
     */
    public Component render(String key, Map<String, String> placeholders) {
        String text = getRaw(key);
        if (text.isEmpty()) {
            return Component.empty();
        }
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return ColorParser.parse(text);
    }

    /**
     * Renders a message key prefixed by the configured chat prefix.
     */
    public Component renderWithPrefix(String key) {
        return renderWithPrefix(key, Collections.emptyMap());
    }

    /**
     * Renders a message key with placeholder replacements prefixed by the chat prefix.
     */
    public Component renderWithPrefix(String key, Map<String, String> placeholders) {
        Component prefix = ColorParser.parse(getRaw("prefix"));
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
     * Loads messages directly from an in-memory configuration section (primarily for testing).
     */
    public static MessageRegistry fromMaps(Map<String, String> active, Map<String, String> fallback, String lang, Logger logger) {
        MessageRegistry registry = new MessageRegistry(null, lang, logger);
        registry.activeMessages = new HashMap<>(active);
        registry.fallbackMessages = new HashMap<>(fallback);
        registry.bundledActiveMessages = Collections.emptyMap();
        registry.bundledFallbackMessages = Collections.emptyMap();
        return registry;
    }

    private Map<String, String> loadFromDiskOrJar(String lang, Map<String, String> bundled) {
        if (dataFolder == null) {
            return bundled != null ? bundled : Collections.emptyMap();
        }

        File file = new File(dataFolder, "messages_" + lang + ".yml");
        if (!file.exists()) {
            // Write to data folder on first run (T-032a)
            saveBundledResource(lang, file);
        }

        if (file.exists()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                return flattenKeys(yaml);
            } catch (Exception e) {
                logger.warning("[SocialBlueprint] Failed to load messages_" + lang + ".yml from disk: " + e.getMessage());
            }
        }

        return bundled != null ? bundled : Collections.emptyMap();
    }

    private void saveBundledResource(String lang, File targetFile) {
        String resourcePath = "messages_" + lang + ".yml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            targetFile.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warning("[SocialBlueprint] Could not extract default " + resourcePath + ": " + e.getMessage());
        }
    }

    private Map<String, String> loadFromJar(String lang) {
        String resourcePath = "messages_" + lang + ".yml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                return flattenKeys(yaml);
            }
        } catch (Exception e) {
            logger.warning("[SocialBlueprint] Could not read bundled " + resourcePath + ": " + e.getMessage());
            return Collections.emptyMap();
        }
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
