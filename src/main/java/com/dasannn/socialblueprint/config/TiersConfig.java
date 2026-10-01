package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.TierLadder;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Immutable configuration for the tier ladder section per T-030, T-031, T-036, and T-112.
 * Validates presence of all nine tiers, strictly ordered thresholds, and names the offending key on failure.
 * Symmetric negative thresholds (-5, -15, -30, -50) are default per SB-011a.
 * On load, existing server configurations with differing negative thresholds are preserved and logged (T-112).
 */
public record TiersConfig(
        Map<Tier, TierConfig> tiers,
        TierLadder ladder
) {
    /**
     * Default symmetric tier ladder thresholds per T-112 and SB-011a.
     */
    public static final Map<Tier, Integer> DEFAULT_THRESHOLDS = Map.of(
            Tier.CRIMINAL, -50,
            Tier.FORAJIDO, -30,
            Tier.DELINCUENTE, -15,
            Tier.TEMERARIO, -5,
            Tier.PARTICULAR, 0,
            Tier.AFABLE, 5,
            Tier.HONORABLE, 15,
            Tier.INSIGNE, 30,
            Tier.ILUSTRE, 50
    );

    /**
     * Former shipped negative tier ladder thresholds (-30, -20, -10, -1).
     */
    public static final Map<Tier, Integer> LEGACY_SHIPPED_THRESHOLDS = Map.of(
            Tier.CRIMINAL, -30,
            Tier.FORAJIDO, -20,
            Tier.DELINCUENTE, -10,
            Tier.TEMERARIO, -1
    );

    private static final java.util.Set<String> REPORTED_MIGRATIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void resetMigrationNotices() {
        REPORTED_MIGRATIONS.clear();
    }

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
     * Loads and strictly validates the tier ladder from configuration without a logger.
     */
    public static TiersConfig load(ConfigurationSection root) {
        return load(root, null);
    }

    /**
     * Loads and strictly validates the tier ladder from configuration.
     * Supports both `tiers.<tierKey>` and root `<tierKey>` paths.
     * Reports migration difference for negative tiers without silently reclassifying (T-112).
     */
    public static TiersConfig load(ConfigurationSection root, Logger logger) {
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
            ColorParser.validate(prefix, prefixKey);

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

            // Report migration difference for legacy shipped negative tiers once per migration (T-112, SB-011a)
            if (tier.isNegative() && logger != null) {
                Integer legacyThreshold = LEGACY_SHIPPED_THRESHOLDS.get(tier);
                Integer defThreshold = DEFAULT_THRESHOLDS.get(tier);
                if (legacyThreshold != null && threshold == legacyThreshold && defThreshold != null && threshold != defThreshold) {
                    String noticeKey = logger.getName() + ":" + tier.configKey() + ":" + threshold;
                    if (REPORTED_MIGRATIONS.add(noticeKey)) {
                        logger.info(String.format(
                                "[SocialBlueprint] Tier '%s' (%s) stored threshold %d differs from default %d; keeping stored threshold",
                                tier.displayName(), tier.configKey(), threshold, defThreshold
                        ));
                    }
                }
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
