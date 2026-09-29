package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.TierLadder;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for the tier ladder section per T-030, T-031, and T-036.
 * Validates presence of all nine tiers, strictly ordered thresholds, and names the offending key on failure.
 */
public record TiersConfig(
        Map<Tier, TierConfig> tiers,
        TierLadder ladder
) {
    public TiersConfig {
        Objects.requireNonNull(tiers, "Tiers map must not be null");
        Objects.requireNonNull(ladder, "TierLadder must not be null");
        tiers = Collections.unmodifiableMap(new EnumMap<>(tiers));
    }

    public TierConfig get(Tier tier) {
        return tiers.get(tier);
    }

    public String prefix(Tier tier) {
        TierConfig cfg = tiers.get(tier);
        return cfg != null ? cfg.prefix() : "";
    }

    /**
     * Loads and strictly validates the tier ladder from configuration.
     * Supports both `tiers.<tierKey>` and root `<tierKey>` paths.
     */
    public static TiersConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");

        ConfigurationSection tiersSection = root.getConfigurationSection("tiers");
        boolean useNested = (tiersSection != null);
        String prefixPath = useNested ? "tiers." : "";

        Map<Tier, TierConfig> tierConfigs = new EnumMap<>(Tier.class);
        Map<Tier, Integer> thresholds = new EnumMap<>(Tier.class);

        for (Tier tier : Tier.values()) {
            String tierKey = tier.configKey();
            String fullTierPath = prefixPath + tierKey;

            ConfigurationSection section = useNested
                    ? tiersSection.getConfigurationSection(tierKey)
                    : root.getConfigurationSection(tierKey);

            if (section == null) {
                // If nested was present but tier was at root, check root as fallback
                if (useNested && root.isConfigurationSection(tierKey)) {
                    section = root.getConfigurationSection(tierKey);
                    fullTierPath = tierKey;
                } else {
                    throw new ConfigValidationException(fullTierPath, "Missing required tier section for '" + tier.displayName() + "'");
                }
            }

            // Check prefix
            String prefixKey = fullTierPath + ".prefix";
            if (!section.contains("prefix")) {
                throw new ConfigValidationException(prefixKey, "Missing required prefix for tier '" + tier.displayName() + "'");
            }
            String prefix = section.getString("prefix");
            if (prefix == null || prefix.isBlank()) {
                throw new ConfigValidationException(prefixKey, "Prefix cannot be blank for tier '" + tier.displayName() + "'");
            }

            // Check threshold (support 'threshold' or baseline 'repRequired')
            String thresholdKey = fullTierPath + ".threshold";
            int threshold;
            if (section.contains("threshold")) {
                if (!section.isInt("threshold")) {
                    throw new ConfigValidationException(thresholdKey, "Threshold must be an integer, got: " + section.get("threshold"));
                }
                threshold = section.getInt("threshold");
            } else if (section.contains("repRequired")) {
                thresholdKey = fullTierPath + ".repRequired";
                if (!section.isInt("repRequired")) {
                    throw new ConfigValidationException(thresholdKey, "Threshold must be an integer, got: " + section.get("repRequired"));
                }
                threshold = section.getInt("repRequired");
            } else {
                throw new ConfigValidationException(thresholdKey, "Missing required threshold for tier '" + tier.displayName() + "'");
            }

            // Explicit validation naming the offending key
            if (tier.isNeutral() && threshold != 0) {
                throw new ConfigValidationException(thresholdKey,
                        "Neutral tier '" + tier.displayName() + "' threshold must be exactly 0, got " + threshold);
            }
            if (tier.isNegative() && threshold >= 0) {
                throw new ConfigValidationException(thresholdKey,
                        "Negative tier '" + tier.displayName() + "' threshold must be strictly negative (< 0), got " + threshold);
            }
            if (tier.isPositive() && threshold <= 0) {
                throw new ConfigValidationException(thresholdKey,
                        "Positive tier '" + tier.displayName() + "' threshold must be strictly positive (> 0), got " + threshold);
            }

            tierConfigs.put(tier, new TierConfig(tier, prefix, threshold));
            thresholds.put(tier, threshold);
        }

        // Validate strictly ascending order of thresholds across tiers
        validateOrder(thresholds, prefixPath);

        TierLadder ladder;
        try {
            ladder = new TierLadder(thresholds);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(prefixPath + "ladder", e.getMessage());
        }

        return new TiersConfig(tierConfigs, ladder);
    }

    private static void validateOrder(Map<Tier, Integer> thresholds, String prefixPath) {
        int tCrim = thresholds.get(Tier.CRIMINAL);
        int tFor = thresholds.get(Tier.FORAJIDO);
        int tDel = thresholds.get(Tier.DELINCUENTE);
        int tTem = thresholds.get(Tier.TEMERARIO);
        int tAfa = thresholds.get(Tier.AFABLE);
        int tHon = thresholds.get(Tier.HONORABLE);
        int tIns = thresholds.get(Tier.INSIGNE);
        int tIlu = thresholds.get(Tier.ILUSTRE);

        // Negative: tCrim < tFor < tDel < tTem < 0
        if (tCrim >= tFor) {
            throw new ConfigValidationException(prefixPath + Tier.CRIMINAL.configKey() + ".threshold",
                    "Tier '" + Tier.CRIMINAL.displayName() + "' threshold (" + tCrim + ") must be strictly less than '"
                            + Tier.FORAJIDO.displayName() + "' threshold (" + tFor + ")");
        }
        if (tFor >= tDel) {
            throw new ConfigValidationException(prefixPath + Tier.FORAJIDO.configKey() + ".threshold",
                    "Tier '" + Tier.FORAJIDO.displayName() + "' threshold (" + tFor + ") must be strictly less than '"
                            + Tier.DELINCUENTE.displayName() + "' threshold (" + tDel + ")");
        }
        if (tDel >= tTem) {
            throw new ConfigValidationException(prefixPath + Tier.DELINCUENTE.configKey() + ".threshold",
                    "Tier '" + Tier.DELINCUENTE.displayName() + "' threshold (" + tDel + ") must be strictly less than '"
                            + Tier.TEMERARIO.displayName() + "' threshold (" + tTem + ")");
        }

        // Positive: 0 < tAfa < tHon < tIns < tIlu
        if (tHon <= tAfa) {
            throw new ConfigValidationException(prefixPath + Tier.HONORABLE.configKey() + ".threshold",
                    "Tier '" + Tier.HONORABLE.displayName() + "' threshold (" + tHon + ") must be strictly greater than '"
                            + Tier.AFABLE.displayName() + "' threshold (" + tAfa + ")");
        }
        if (tIns <= tHon) {
            throw new ConfigValidationException(prefixPath + Tier.INSIGNE.configKey() + ".threshold",
                    "Tier '" + Tier.INSIGNE.displayName() + "' threshold (" + tIns + ") must be strictly greater than '"
                            + Tier.HONORABLE.displayName() + "' threshold (" + tHon + ")");
        }
        if (tIlu <= tIns) {
            throw new ConfigValidationException(prefixPath + Tier.ILUSTRE.configKey() + ".threshold",
                    "Tier '" + Tier.ILUSTRE.displayName() + "' threshold (" + tIlu + ") must be strictly greater than '"
                            + Tier.INSIGNE.displayName() + "' threshold (" + tIns + ")");
        }
    }
}
