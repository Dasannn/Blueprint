package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

/**
 * Immutable configuration section for rating history and anonymity per SB-084 and T-124.
 * Defines the reveal cost charged through Vault to unmask an anonymous rater.
 */
public record HistoryConfig(double revealCost, int revealConfirmSeconds) {

    public static final double DEFAULT_REVEAL_COST = 100.0;

    public HistoryConfig(double revealCost) {
        this(revealCost, 5);
    }

    public HistoryConfig {
        if (revealConfirmSeconds < 1 || revealConfirmSeconds > 60) {
            throw new ConfigValidationException("history.reveal-confirm-seconds",
                    "Reveal confirmation seconds must be 1..60, got: " + revealConfirmSeconds);
        }
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

        if (section.contains("reveal-cost") && !section.isDouble("reveal-cost") && !section.isInt("reveal-cost")) {
            throw new ConfigValidationException("history.reveal-cost",
                    "Reveal cost must be a numeric value, got: " + section.get("reveal-cost"));
        }

        if (section.contains("reveal-confirm-seconds") && !section.isInt("reveal-confirm-seconds")) {
            throw new ConfigValidationException("history.reveal-confirm-seconds",
                    "Reveal confirmation seconds must be an integer, got: " + section.get("reveal-confirm-seconds"));
        }
        return new HistoryConfig(section.getDouble("reveal-cost", DEFAULT_REVEAL_COST),
                section.getInt("reveal-confirm-seconds", 5));
    }
}
