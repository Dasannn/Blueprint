package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration section for automatic non-duel kill status penalty
 * per SB-032, SB-034, SB-035, SB-036, and Decision 0004.
 */
public record KillPenaltyConfigSection(
        int delta,
        Duration pairCooldown,
        Duration capWindow,
        int maxLoss,
        Set<String> exemptWorlds
) {
    public KillPenaltyConfigSection {
        Objects.requireNonNull(pairCooldown, "pairCooldown must not be null");
        Objects.requireNonNull(capWindow, "capWindow must not be null");
        Objects.requireNonNull(exemptWorlds, "exemptWorlds must not be null");

        if (delta > 0) {
            throw new ConfigValidationException("kill-penalty.delta", "Kill penalty delta must be non-positive, got: " + delta);
        }
        if (maxLoss < 0) {
            throw new ConfigValidationException("kill-penalty.max-loss", "Kill penalty max-loss must not be negative, got: " + maxLoss);
        }
    }

    public boolean isEnabled() {
        return delta != 0;
    }

    public boolean isWorldExempt(String worldName) {
        if (worldName == null || exemptWorlds.isEmpty()) {
            return false;
        }
        String normalized = worldName.trim().toLowerCase(Locale.ROOT);
        return exemptWorlds.stream().anyMatch(w -> w.trim().equalsIgnoreCase(normalized));
    }

    public static KillPenaltyConfigSection defaults() {
        return new KillPenaltyConfigSection(
                -1,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                Collections.emptySet()
        );
    }

    public static KillPenaltyConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "root ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("kill-penalty");
        if (section == null) {
            return defaults();
        }

        int delta = section.getInt("delta", -1);
        if (delta > 0) {
            throw new ConfigValidationException("kill-penalty.delta", "Kill penalty delta must be non-positive, got: " + delta);
        }

        Duration pairCooldown = Duration.ofMinutes(30);
        if (section.contains("pair-cooldown")) {
            pairCooldown = DurationParser.parseNonNegative(section.getString("pair-cooldown"), "kill-penalty.pair-cooldown");
        }

        Duration capWindow = Duration.ofDays(7);
        if (section.contains("cap-window")) {
            capWindow = DurationParser.parsePositive(section.getString("cap-window"), "kill-penalty.cap-window");
        }

        int maxLoss = section.getInt("max-loss", 10);
        if (maxLoss < 0) {
            throw new ConfigValidationException("kill-penalty.max-loss", "Kill penalty max-loss must not be negative, got: " + maxLoss);
        }

        Set<String> exempt = new HashSet<>();
        if (section.contains("exempt-worlds")) {
            if (section.isList("exempt-worlds")) {
                List<String> list = section.getStringList("exempt-worlds");
                for (String w : list) {
                    if (w != null && !w.isBlank()) {
                        exempt.add(w.trim());
                    }
                }
            } else {
                String single = section.getString("exempt-worlds");
                if (single != null && !single.isBlank()) {
                    exempt.add(single.trim());
                }
            }
        }

        return new KillPenaltyConfigSection(delta, pairCooldown, capWindow, maxLoss, Collections.unmodifiableSet(exempt));
    }
}
