package com.dasannn.socialblueprint.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The nine reputation tiers specified in SB-010 and Constitution §4.
 * Spanish names and visual identity from the baseline are preserved.
 */
public enum Tier {
    CRIMINAL("Criminal", "tier-4", -4),
    FORAJIDO("Forajido", "tier-3", -3),
    DELINCUENTE("Delincuente", "tier-2", -2),
    TEMERARIO("Temerario", "tier-1", -1),
    PARTICULAR("Particular", "tier0", 0),
    AFABLE("Afable", "tier1", 1),
    HONORABLE("Honorable", "tier2", 2),
    INSIGNE("Insigne", "tier3", 3),
    ILUSTRE("Ilustre", "tier4", 4);

    private final String displayName;
    private final String configKey;
    private final int level;

    Tier(String displayName, String configKey, int level) {
        this.displayName = displayName;
        this.configKey = configKey;
        this.level = level;
    }

    public String displayName() {
        return displayName;
    }

    public String configKey() {
        return configKey;
    }

    public int level() {
        return level;
    }

    public boolean isNegative() {
        return level < 0;
    }

    public boolean isNeutral() {
        return level == 0;
    }

    public boolean isPositive() {
        return level > 0;
    }

    public static Tier fromConfigKey(String key) {
        Objects.requireNonNull(key, "Config key must not be null");
        String trimmed = key.trim().toLowerCase(Locale.ROOT);
        for (Tier tier : values()) {
            if (tier.configKey.equalsIgnoreCase(trimmed)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown tier config key: " + key);
    }

    public static Tier fromDisplayName(String name) {
        Objects.requireNonNull(name, "Display name must not be null");
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        for (Tier tier : values()) {
            if (tier.displayName.equalsIgnoreCase(trimmed) || tier.name().equalsIgnoreCase(trimmed)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown tier name: " + name);
    }
}
