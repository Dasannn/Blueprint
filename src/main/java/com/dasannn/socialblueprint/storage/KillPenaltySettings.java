package com.dasannn.socialblueprint.storage;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Plain parameter object for kill penalty evaluation and transaction per Finding 1.
 * Holds immutable primitive/standard Java types with no dependency on config or Bukkit.
 */
public record KillPenaltySettings(
        boolean enabled,
        int delta,
        Duration pairCooldown,
        Duration capWindow,
        int maxLoss,
        Set<String> exemptWorlds
) {
    public KillPenaltySettings {
        Objects.requireNonNull(pairCooldown, "pairCooldown must not be null");
        Objects.requireNonNull(capWindow, "capWindow must not be null");
        exemptWorlds = exemptWorlds != null ? Set.copyOf(exemptWorlds) : Collections.emptySet();
    }

    public boolean isWorldExempt(String worldName) {
        if (worldName == null || exemptWorlds.isEmpty()) {
            return false;
        }
        String normalized = worldName.trim();
        return exemptWorlds.stream().anyMatch(w -> w.trim().equalsIgnoreCase(normalized));
    }
}
