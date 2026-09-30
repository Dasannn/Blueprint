package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.DecayConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration section for reputation decay per T-110, SB-006, and Constitution §2.6.
 */
public record DecayConfigSection(
        boolean enabled,
        Duration halfLife,
        double floor,
        Duration cacheTtl
) {
    public DecayConfigSection {
        Objects.requireNonNull(halfLife, "halfLife must not be null");
        Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
    }

    public DecayConfig toDomain() {
        return new DecayConfig(enabled, halfLife, floor, cacheTtl);
    }

    public static DecayConfigSection defaults() {
        return new DecayConfigSection(true, Duration.ofDays(30), 0.0, Duration.ofSeconds(60));
    }

    public static DecayConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        if (!root.contains("decay")) {
            // An existing server's configuration predates this section. Upgrading must
            // not refuse to enable, so an absent block means the shipped defaults; a
            // block that is present is still validated strictly.
            return defaults();
        }
        if (!root.isConfigurationSection("decay")) {
            throw new ConfigValidationException("decay", "decay must be a configuration section, got: " + root.get("decay"));
        }
        ConfigurationSection section = root.getConfigurationSection("decay");

        String enabledKey = "decay.enabled";
        if (!section.contains("enabled")) {
            throw new ConfigValidationException(enabledKey, "Missing required key: " + enabledKey);
        }
        if (!section.isBoolean("enabled")) {
            throw new ConfigValidationException(enabledKey, "decay.enabled must be a boolean (true/false), got: " + section.get("enabled"));
        }
        boolean enabled = section.getBoolean("enabled");

        String halfLifeKey = "decay.half-life";
        if (!section.contains("half-life")) {
            throw new ConfigValidationException(halfLifeKey, "Missing required key: " + halfLifeKey);
        }
        Duration halfLife = DurationParser.parsePositive(section.getString("half-life"), halfLifeKey);
        if (halfLife.compareTo(DecayConfig.MAX_HALF_LIFE) > 0) {
            throw new ConfigValidationException(halfLifeKey, "decay.half-life exceeds maximum supported duration of 100 years ("
                    + DecayConfig.MAX_HALF_LIFE + "), got " + halfLife);
        }

        String floorKey = "decay.floor";
        if (!section.contains("floor")) {
            throw new ConfigValidationException(floorKey, "Missing required key: " + floorKey);
        }
        double floor = parseDouble(section, "floor", floorKey);
        if (floor < 0.0 || floor > 1.0) {
            throw new ConfigValidationException(floorKey, "decay.floor must be within [0.0, 1.0], got " + floor);
        }

        String cacheTtlKey = "decay.cache-ttl";
        Duration cacheTtl;
        if (section.contains("cache-ttl")) {
            cacheTtl = DurationParser.parsePositive(section.getString("cache-ttl"), cacheTtlKey);
        } else {
            cacheTtl = DecayConfig.DEFAULT_CACHE_TTL;
        }

        return new DecayConfigSection(enabled, halfLife, floor, cacheTtl);
    }

    private static double parseDouble(ConfigurationSection section, String subKey, String fullKey) {
        double val;
        if (!section.isDouble(subKey) && !section.isInt(subKey)) {
            try {
                val = Double.parseDouble(section.getString(subKey, ""));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(fullKey, "Value must be a valid number, got: " + section.get(subKey));
            }
        } else {
            val = section.getDouble(subKey);
        }
        if (!Double.isFinite(val)) {
            throw new ConfigValidationException(fullKey, "Value must be finite, got: " + val);
        }
        return val;
    }
}
