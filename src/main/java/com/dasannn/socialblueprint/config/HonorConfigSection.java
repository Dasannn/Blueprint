package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.HonorAllowanceConfig;
import com.dasannn.socialblueprint.domain.HonorCostConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration section for the Honor economy per T-030, T-031, and Decision 0001.
 */
public record HonorConfigSection(
        double cost,
        List<Double> multipliers,
        Duration window,
        Duration cooldownPerPair,
        int maxPerTarget
) {
    public HonorConfigSection {
        Objects.requireNonNull(multipliers, "Multipliers list must not be null");
        Objects.requireNonNull(window, "Window duration must not be null");
        Objects.requireNonNull(cooldownPerPair, "Cooldown duration must not be null");
        multipliers = Collections.unmodifiableList(new ArrayList<>(multipliers));
    }

    public HonorCostConfig toCostConfig() {
        return new HonorCostConfig(cost, multipliers, window);
    }

    public HonorAllowanceConfig toAllowanceConfig() {
        return new HonorAllowanceConfig(maxPerTarget, window);
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
        if (cost <= 0.0) {
            throw new ConfigValidationException(costKey, "Base cost must be strictly positive (> 0), got: " + cost);
        }

        String multKey = "honor.multipliers";
        if (!section.contains("multipliers")) {
            throw new ConfigValidationException(multKey, "Missing required key: " + multKey);
        }
        List<?> rawMultipliers = section.getList("multipliers");
        if (rawMultipliers == null || rawMultipliers.isEmpty()) {
            throw new ConfigValidationException(multKey, "Multipliers list must not be empty");
        }
        List<Double> multipliers = new ArrayList<>(rawMultipliers.size());
        for (int i = 0; i < rawMultipliers.size(); i++) {
            Object obj = rawMultipliers.get(i);
            String itemKey = multKey + "[" + i + "]";
            double m;
            if (obj instanceof Number num) {
                m = num.doubleValue();
            } else {
                try {
                    m = Double.parseDouble(String.valueOf(obj));
                } catch (NumberFormatException e) {
                    throw new ConfigValidationException(itemKey, "Multiplier must be a valid number, got: " + obj);
                }
            }
            if (!Double.isFinite(m) || m <= 0.0) {
                throw new ConfigValidationException(itemKey, "Multiplier must be finite and strictly positive (> 0), got: " + m);
            }
            if (i > 0 && m < multipliers.get(i - 1)) {
                throw new ConfigValidationException(itemKey, "Multipliers must be non-decreasing: " + m
                        + " < previous " + multipliers.get(i - 1));
            }
            multipliers.add(m);
        }

        String windowKey = "honor.window";
        if (!section.contains("window")) {
            throw new ConfigValidationException(windowKey, "Missing required key: " + windowKey);
        }
        Duration window = DurationParser.parsePositive(section.getString("window"), windowKey);

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

        return new HonorConfigSection(cost, multipliers, window, cooldown, maxPerTarget);
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
