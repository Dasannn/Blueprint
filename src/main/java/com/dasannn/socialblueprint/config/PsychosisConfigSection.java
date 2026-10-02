package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.PsychosisConfig;
import com.dasannn.socialblueprint.domain.ChatCorruptionConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration section for Killing Psychosis per T-030 and T-031.
 */
public record PsychosisConfigSection(
        Duration window,
        int mediumThreshold,
        int highThreshold,
        int extremeThreshold,
        ChatCorruptionConfig chat,
        com.dasannn.socialblueprint.domain.SerenityConfig serenity
) {
    public PsychosisConfigSection(Duration window, int mediumThreshold, int highThreshold, int extremeThreshold, ChatCorruptionConfig chat) {
        this(window, mediumThreshold, highThreshold, extremeThreshold, chat, com.dasannn.socialblueprint.domain.SerenityConfig.DEFAULT);
    }
    public PsychosisConfigSection(Duration window, int mediumThreshold, int highThreshold, int extremeThreshold) {
        this(window, mediumThreshold, highThreshold, extremeThreshold, ChatCorruptionConfig.DEFAULT);
    }

    public PsychosisConfigSection {
        Objects.requireNonNull(chat, "Chat configuration must not be null");
        Objects.requireNonNull(serenity, "Serenity configuration must not be null");
        Objects.requireNonNull(window, "Window duration must not be null");
    }

    public PsychosisConfig toDomain() {
        return new PsychosisConfig(window, mediumThreshold, highThreshold, extremeThreshold);
    }

    public static PsychosisConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("psychosis");
        if (section == null) {
            throw new ConfigValidationException("psychosis", "Missing required configuration section 'psychosis'");
        }

        String windowKey = "psychosis.window";
        if (!section.contains("window")) {
            throw new ConfigValidationException(windowKey, "Missing required key: " + windowKey);
        }
        Duration window = DurationParser.parsePositive(section.getString("window"), windowKey);

        String medKey = "psychosis.medium-threshold";
        if (!section.contains("medium-threshold")) {
            throw new ConfigValidationException(medKey, "Missing required key: " + medKey);
        }
        int medium = parseInt(section, "medium-threshold", medKey);
        if (medium <= 0) {
            throw new ConfigValidationException(medKey, "medium-threshold must be > 0, got " + medium);
        }

        String highKey = "psychosis.high-threshold";
        if (!section.contains("high-threshold")) {
            throw new ConfigValidationException(highKey, "Missing required key: " + highKey);
        }
        int high = parseInt(section, "high-threshold", highKey);
        if (high <= medium) {
            throw new ConfigValidationException(highKey, "high-threshold must be > medium-threshold ("
                    + medium + "), got " + high);
        }

        String extKey = "psychosis.extreme-threshold";
        if (!section.contains("extreme-threshold")) {
            throw new ConfigValidationException(extKey, "Missing required key: " + extKey);
        }
        int extreme = parseInt(section, "extreme-threshold", extKey);
        if (extreme <= high) {
            throw new ConfigValidationException(extKey, "extreme-threshold must be > high-threshold ("
                    + high + "), got " + extreme);
        }

        return new PsychosisConfigSection(window, medium, high, extreme, loadChat(root),
                new com.dasannn.socialblueprint.domain.SerenityConfig(
                        serenityNumber(root, "ceiling", 100),
                        serenityNumber(root, "active-hours-to-ceiling", 100),
                        serenityNumber(root, "idle-timeout-seconds", 300)));
    }

    private static double serenityNumber(ConfigurationSection root, String leaf, double fallback) {
        String key = "psychosis.serenity." + leaf;
        Object raw = root.get(key);
        if (raw == null) return fallback;
        double value;
        try { value = Double.parseDouble(raw.toString()); }
        catch (NumberFormatException ex) { throw new ConfigValidationException(key, "Must be a positive finite number"); }
        double scaled = value * (leaf.equals("active-hours-to-ceiling") ? 3_600_000
                : leaf.equals("idle-timeout-seconds") ? 1000 : 1);
        if (!Double.isFinite(value) || value <= 0 || !Double.isFinite(scaled) || scaled == 0) {
            throw new ConfigValidationException(key, "Must be a positive finite number");
        }

        if (root.contains("psychosis.serenity") && !root.isConfigurationSection("psychosis.serenity"))
            throw new ConfigValidationException("psychosis.serenity", "Must be a mapping");
        ConfigurationSection serenitySection = root.getConfigurationSection("psychosis.serenity");
        if (serenitySection != null) for (String child : serenitySection.getKeys(false)) {
            if (!java.util.Set.of("ceiling", "active-hours-to-ceiling", "idle-timeout-seconds").contains(child))
                throw new ConfigValidationException("psychosis.serenity." + child, "Unknown key");
        }
        return value;
    }

    private static ChatCorruptionConfig loadChat(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("psychosis.chat");
        if (section == null) return ChatCorruptionConfig.DEFAULT;
        int extent = integer(section, "extent", 20);
        if (extent < 1 || extent > 25) {
            throw new ConfigValidationException("psychosis.chat.extent", "Extent must be between 1 and 25 percent");
        }
        int minLetters = integer(section, "min-letters", 6);
        if (minLetters < 1) throw new ConfigValidationException("psychosis.chat.min-letters", "Must be positive");
        try {
            return new ChatCorruptionConfig(integer(section, "medium-rate", 10), integer(section, "high-rate", 25),
                    integer(section, "extreme-rate", 40), extent, minLetters);
        } catch (IllegalArgumentException ex) {
            throw new ConfigValidationException("psychosis.chat", ex.getMessage());
        }
    }

    private static int integer(ConfigurationSection section, String key, int fallback) {
        return section.contains(key) ? parseInt(section, key, "psychosis.chat." + key) : fallback;
    }

    private static int parseInt(ConfigurationSection section, String subKey, String fullKey) {
        if (!section.isInt(subKey)) {
            try {
                return Integer.parseInt(section.getString(subKey, ""));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(fullKey, "Value must be a valid integer, got: " + section.get(subKey));
            }
        }
        return section.getInt(subKey);
    }
}
