package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;
import java.util.Set;

public record ForeignRendererConfig(String mode, String prefix) {
    public ForeignRendererConfig {
        validate("mode", mode, Set.of("wrap", "leave"));
        validate("prefix", prefix, Set.of("before-line", "display-name", "none"));
    }

    public static ForeignRendererConfig defaults() {
        return new ForeignRendererConfig("wrap", "before-line");
    }

    public static ForeignRendererConfig load(ConfigurationSection root) {
        return new ForeignRendererConfig(read(root, "mode", "wrap"), read(root, "prefix", "before-line"));
    }

    private static String read(ConfigurationSection root, String leaf, String fallback) {
        String key = "chat.foreign-renderer." + leaf;
        if (!root.contains(key)) return fallback;
        if (!(root.get(key) instanceof String value))
            throw new ConfigValidationException(key, "Must be a string");
        return value;
    }

    private static void validate(String leaf, String value, Set<String> choices) {
        if (value == null || !choices.contains(value))
            throw new ConfigValidationException("chat.foreign-renderer." + leaf, "Must be one of " + choices);
    }
}
