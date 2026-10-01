package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

/**
 * Immutable configuration section for rating history and anonymity per SB-084 and T-124.
 * Defines the reveal cost charged through Vault to unmask an anonymous rater.
 */
public record HistoryConfig(double revealCost) {

    public static final double DEFAULT_REVEAL_COST = 100.0;

    public HistoryConfig {
        if (!Double.isFinite(revealCost) || revealCost < 0.0) {
            throw new ConfigValidationException("history.reveal-cost",
                    "Reveal cost must be non-negative and finite, got: " + revealCost);
        }
    }

    public static HistoryConfig defaults() {
        return new HistoryConfig(DEFAULT_REVEAL_COST);
    }

    public static HistoryConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("history");
        if (section == null) {
            return defaults();
        }

        if (!section.contains("reveal-cost")) {
            return defaults();
        }

        if (!section.isDouble("reveal-cost") && !section.isInt("reveal-cost")) {
            throw new ConfigValidationException("history.reveal-cost",
                    "Reveal cost must be a numeric value, got: " + section.get("reveal-cost"));
        }

        double cost = section.getDouble("reveal-cost");
        return new HistoryConfig(cost);
    }
}
