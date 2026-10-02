package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.*;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;

public record PsychosisConfigSection(double mediumThreshold, double highThreshold, double extremeThreshold,
        ChatCorruptionConfig chat, SerenityConfig serenity, Map<MindInput, MindInputConfig> inputs,
        double nearDeathHealth, double cleanDayActiveMinutes, int peacefulCap) {
    public PsychosisConfigSection {
        inputs = Map.copyOf(inputs);
        Objects.requireNonNull(chat);
        Objects.requireNonNull(serenity);
        new PsychosisConfig(mediumThreshold, highThreshold, extremeThreshold);
    }
    public PsychosisConfig toDomain() { return new PsychosisConfig(mediumThreshold, highThreshold, extremeThreshold); }
    public MindInputConfig input(MindInput kind) { return inputs.get(kind); }

    public static PsychosisConfigSection load(ConfigurationSection root) {
        if (root.getConfigurationSection("psychosis") == null)
            throw new ConfigValidationException("psychosis", "Missing required configuration section");
        for (String level : java.util.List.of("low", "medium", "high", "extreme")) {
            String key = "psychosis.levels." + level;
            if (!root.contains(key)) throw new ConfigValidationException(key, "Missing required key");
        }
        double low = number(root, "psychosis.levels.low", 0);
        if (low != 0) throw new ConfigValidationException("psychosis.levels.low", "Low begins above the fixed Neutral boundary 0");
        double medium = number(root, "psychosis.levels.medium", 20);
        double high = number(root, "psychosis.levels.high", 50);
        double extreme = number(root, "psychosis.levels.extreme", 80);
        if (medium <= 0 || medium > 100) throw new ConfigValidationException("psychosis.levels.medium", "Must be inside (0, 100]");
        if (high <= medium || high > 100) throw new ConfigValidationException("psychosis.levels.high", "Must exceed Medium and be at most 100");
        if (extreme <= high || extreme > 100) throw new ConfigValidationException("psychosis.levels.extreme", "Must exceed High and be at most 100");
        for (String key : java.util.List.of("psychosis.levels", "psychosis.inputs", "psychosis.inputs.peaceful", "psychosis.serenity")) {
            if (root.contains(key) && !root.isConfigurationSection(key)) throw new ConfigValidationException(key, "Must be a mapping");
        }
        int peacefulCap = cap(root, "psychosis.inputs.peaceful.cap", 25);
        Map<MindInput, MindInputConfig> inputs = new EnumMap<>(MindInput.class);
        for (MindInput kind : MindInput.values()) {
            String prefix = "psychosis.inputs." + kind.id() + ".";
            if (root.contains("psychosis.inputs." + kind.id()) && !root.isConfigurationSection("psychosis.inputs." + kind.id()))
                throw new ConfigValidationException("psychosis.inputs." + kind.id(), "Must be a mapping");
            Object enabled = root.get(prefix + "enabled", true);
            if (!(enabled instanceof Boolean)) throw new ConfigValidationException(prefix + "enabled", "Must be a boolean");
            MindInputConfig defaults = kind.defaults();
            inputs.put(kind, new MindInputConfig((Boolean) enabled,
                    number(root, prefix + (kind.bad() ? "serene-drain" : "gain"), defaults.sereneAmount()),
                    number(root, prefix + (kind.bad() ? "psychosis-weight" : "cure"), defaults.psychosisAmount()),
                    kind.peaceful() ? peacefulCap : kind.bad() ? 0 : cap(root, prefix + "cap", defaults.cap())));
        }
        double health = number(root, "psychosis.inputs.near-death.health", 4);
        if (health <= 0) throw new ConfigValidationException("psychosis.inputs.near-death.health", "Must be positive");
        double minutes = number(root, "psychosis.inputs.clean-day.active-minutes", 30);
        if (!Double.isFinite(minutes * 60_000))
            throw new ConfigValidationException("psychosis.inputs.clean-day.active-minutes", "Must be finite in milliseconds");
        double idle = number(root, "psychosis.serenity.idle-timeout-seconds", 300);
        if (idle <= 0 || !Double.isFinite(idle * 1000))
            throw new ConfigValidationException("psychosis.serenity.idle-timeout-seconds", "Must be positive and finite in milliseconds");
        double ceiling = number(root, "psychosis.serenity.ceiling", 100);
        if (ceiling <= 0 || ceiling > 100) throw new ConfigValidationException("psychosis.serenity.ceiling", "Must be inside (0, 100]");
        return new PsychosisConfigSection(medium, high, extreme, loadChat(root),
                new SerenityConfig(ceiling, 100, idle), inputs, health, minutes, peacefulCap);
    }

    private static double number(ConfigurationSection root, String key, double fallback) {
        Object raw = root.get(key, fallback);
        double value;
        try { value = Double.parseDouble(raw.toString()); }
        catch (NumberFormatException ex) { throw new ConfigValidationException(key, "Must be a finite nonnegative number"); }
        if (!Double.isFinite(value) || value < 0) throw new ConfigValidationException(key, "Must be a finite nonnegative number");
        return value;
    }
    private static int cap(ConfigurationSection root, String key, int fallback) {
        double value = number(root, key, fallback);
        if (value != Math.rint(value) || value > Integer.MAX_VALUE) throw new ConfigValidationException(key, "Must be a nonnegative integer");
        return (int) value;
    }

    private static ChatCorruptionConfig loadChat(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("psychosis.chat");
        if (section == null) return ChatCorruptionConfig.DEFAULT;
        int mediumExtent = extent(section, "medium-extent", 20);
        int highExtent = extent(section, "high-extent", 35);
        int extremeExtent = extent(section, "extreme-extent", 50);
        if (highExtent < mediumExtent) throw new ConfigValidationException("psychosis.chat.high-extent", "Must be >= medium-extent");
        if (extremeExtent < highExtent) throw new ConfigValidationException("psychosis.chat.extreme-extent", "Must be >= high-extent");
        int minLetters = integer(section, "min-letters", 6);
        if (minLetters < 1) throw new ConfigValidationException("psychosis.chat.min-letters", "Must be positive");
        try {
            return new ChatCorruptionConfig(integer(section, "medium-rate", 10), integer(section, "high-rate", 25),
                    integer(section, "extreme-rate", 40), mediumExtent, highExtent, extremeExtent, minLetters,
                    section.getString("medium-colour", "#AAAAAA"), section.getString("high-colour", "#666666"),
                    section.getString("extreme-colour", "#303030"));
        } catch (IllegalArgumentException ex) {
            for (String leaf : java.util.List.of("medium-colour", "high-colour", "extreme-colour")) {
                if (ex.getMessage().startsWith(leaf + " "))
                    throw new ConfigValidationException("psychosis.chat." + leaf, ex.getMessage());
            }
            throw new ConfigValidationException("psychosis.chat", ex.getMessage());
        }
    }

    private static int extent(ConfigurationSection section, String key, int fallback) {
        int value = integer(section, key, fallback);
        if (value < 1 || value > 50) throw new ConfigValidationException("psychosis.chat." + key, "Must be between 1 and 50 percent");
        return value;
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
