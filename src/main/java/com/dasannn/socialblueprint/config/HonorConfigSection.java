package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.HonorAllowanceConfig;
import com.dasannn.socialblueprint.domain.HonorCostConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration section for the Honor economy per T-030, T-031, and Decision 0008.
 */
public record HonorConfigSection(
        double cost,
        double costPercent,
        Duration capWindow,
        Duration cooldownPerPair,
        int maxPerTarget,
        int reasonMinLength
) {
    public HonorConfigSection(double cost, double costPercent,
                              Duration capWindow, Duration cooldownPerPair, int maxPerTarget) {
        this(cost, costPercent, capWindow, cooldownPerPair, maxPerTarget, 3);
    }

    public HonorConfigSection {
        if (reasonMinLength < 1 || reasonMinLength > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_REASON_LENGTH)
            throw new ConfigValidationException("honor.reason.min-length", "Must be between 1 and 100");
        Objects.requireNonNull(capWindow, "Cap window duration must not be null");
        Objects.requireNonNull(cooldownPerPair, "Cooldown duration must not be null");
    }

    public HonorCostConfig toCostConfig() {
        return new HonorCostConfig(cost, costPercent);
    }

    public HonorAllowanceConfig toAllowanceConfig() {
        return new HonorAllowanceConfig(maxPerTarget, capWindow);
    }

    public static HonorConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("honor");
        if (section == null) {
            throw new ConfigValidationException("honor", "Missing required configuration section 'honor'");
        }

        String costKey = "honor.cost";
        if (!section.contains("cost")) {
            throw new ConfigValidationException(costKey, "Missing required key: " + costKey);
        }
        double cost = parseDouble(section, "cost", costKey);
        if (cost < 0.0) throw new ConfigValidationException(costKey, "Base cost must be non-negative");
        String percentKey = "honor.cost-percent";
        double costPercent = section.contains("cost-percent") ? parseDouble(section, "cost-percent", percentKey) : 8;
        if (costPercent < 0 || costPercent > 100)
            throw new ConfigValidationException(percentKey, "Must be between 0 and 100");
        if (cost == 0 && costPercent == 0)
            throw new ConfigValidationException(percentKey, "Cost and percent cannot both be zero");
        String capWindowKey = "honor.cap-window";
        if (!section.contains("cap-window") && section.contains("window")) section.set("cap-window", section.getString("window"));

        if (!section.contains("cap-window")) {
            throw new ConfigValidationException(capWindowKey, "Missing required key: " + capWindowKey);
        }
        Duration capWindow = DurationParser.parsePositive(section.getString("cap-window"), capWindowKey);

        String cooldownKey = "honor.cooldown-per-pair";
        if (!section.contains("cooldown-per-pair")) {
            throw new ConfigValidationException(cooldownKey, "Missing required key: " + cooldownKey);
        }
        Duration cooldown = DurationParser.parseNonNegative(section.getString("cooldown-per-pair"), cooldownKey);

        String maxTargetKey = "honor.max-per-target";
        if (!section.contains("max-per-target")) {
            throw new ConfigValidationException(maxTargetKey, "Missing required key: " + maxTargetKey);
        }
        int maxPerTarget = parseInt(section, "max-per-target", maxTargetKey);
        if (maxPerTarget <= 0) {
            throw new ConfigValidationException(maxTargetKey, "max-per-target must be strictly positive (> 0), got: " + maxPerTarget);
        }

        Duration requiredMinimum = cooldown.multipliedBy(maxPerTarget);
        if (capWindow.compareTo(requiredMinimum) <= 0) {
            throw new ConfigValidationException(capWindowKey,
                    "honor.cap-window must be longer than honor.cooldown-per-pair * honor.max-per-target ("
                            + requiredMinimum + "), got: " + capWindow);
        }

        int reasonMinLength = section.contains("reason.min-length")
                ? parseInt(section, "reason.min-length", "honor.reason.min-length") : 3;
        return new HonorConfigSection(cost, costPercent, capWindow, cooldown, maxPerTarget, reasonMinLength);
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
