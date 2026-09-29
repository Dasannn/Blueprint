package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;

import java.util.Objects;

/**
 * Immutable configuration for a single tier per T-030 and T-036.
 * Prefix token and threshold stay in config.yml; display name lives in language files (SB-070i, T-032d).
 */
public record TierConfig(Tier tier, String prefix, int threshold) {

    public TierConfig {
        Objects.requireNonNull(tier, "Tier must not be null");
        Objects.requireNonNull(prefix, "Prefix must not be null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("Prefix for tier '" + tier.configKey() + "' must not be blank");
        }
    }
}
