package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.ConfidenceConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration section for Reputation Confidence per T-030 and T-031.
 */
public record ConfidenceConfigSection(
        double lowThreshold,
        double establishedThreshold,
        double highThreshold,
        Duration halfLife
) {
    public ConfidenceConfigSection {
        Objects.requireNonNull(halfLife, "halfLife must not be null");
    }

    public ConfidenceConfig toDomain() {
        return new ConfidenceConfig(lowThreshold, establishedThreshold, highThreshold, halfLife);
    }

    public static ConfidenceConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("confidence");
        if (section == null) {
            throw new ConfigValidationException("confidence", "Missing required configuration section 'confidence'");
        }

        String halfLifeKey = "confidence.half-life";
        if (!section.contains("half-life")) {
            throw new ConfigValidationException(halfLifeKey, "Missing required key: " + halfLifeKey);
        }
        Duration halfLife = DurationParser.parsePositive(section.getString("half-life"), halfLifeKey);
        if (halfLife.compareTo(ConfidenceConfig.MAX_HALF_LIFE) > 0) {
            throw new ConfigValidationException(halfLifeKey, "half-life exceeds maximum supported duration of 100 years ("
                    + ConfidenceConfig.MAX_HALF_LIFE + "), got " + halfLife);
        }

        String lowKey = "confidence.low-threshold";
        if (!section.contains("low-threshold")) {
            throw new ConfigValidationException(lowKey, "Missing required key: " + lowKey);
        }
        double low = parseDouble(section, "low-threshold", lowKey);
        if (low <= 0.0) {
            throw new ConfigValidationException(lowKey, "low-threshold must be strictly positive (> 0), got " + low);
        }

        String estKey = "confidence.established-threshold";
        if (!section.contains("established-threshold")) {
            throw new ConfigValidationException(estKey, "Missing required key: " + estKey);
        }
        double est = parseDouble(section, "established-threshold", estKey);
        if (est <= low) {
            throw new ConfigValidationException(estKey, "established-threshold must be greater than low-threshold ("
                    + low + "), got " + est);
        }

        String highKey = "confidence.high-threshold";
        if (!section.contains("high-threshold")) {
            throw new ConfigValidationException(highKey, "Missing required key: " + highKey);
        }
        double high = parseDouble(section, "high-threshold", highKey);
        if (high <= est) {
            throw new ConfigValidationException(highKey, "high-threshold must be greater than established-threshold ("
                    + est + "), got " + high);
        }

        return new ConfidenceConfigSection(low, est, high, halfLife);
    }

    private static double parseDouble(ConfigurationSection section, String subKey, String fullKey) {
        if (!section.isDouble(subKey) && !section.isInt(subKey)) {
            try {
                return Double.parseDouble(section.getString(subKey, ""));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(fullKey, "Value must be a valid number, got: " + section.get(subKey));
            }
        }
        double val = section.getDouble(subKey);
        if (!Double.isFinite(val)) {
            throw new ConfigValidationException(fullKey, "Value must be finite, got: " + val);
        }
        return val;
    }
}
