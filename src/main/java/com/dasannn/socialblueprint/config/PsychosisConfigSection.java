package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.PsychosisConfig;
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
        int extremeThreshold
) {
    public PsychosisConfigSection {
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

        return new PsychosisConfigSection(window, medium, high, extreme);
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
