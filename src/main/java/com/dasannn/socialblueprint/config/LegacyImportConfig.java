package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

/**
 * Immutable configuration section for legacy import settings.
 */
public record LegacyImportConfig(
        boolean trustNameLookup
) {
    public static final LegacyImportConfig DEFAULT = new LegacyImportConfig(false);

    public static LegacyImportConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "Root ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("legacy-import");
        if (section == null) {
            return DEFAULT;
        }
        boolean trust = section.getBoolean("trust-name-lookup", false);
        return new LegacyImportConfig(trust);
    }
}
