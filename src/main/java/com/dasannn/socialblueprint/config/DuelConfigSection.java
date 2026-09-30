package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration section for Duels per SB-030, SB-033, T-060, and T-063.
 */
public record DuelConfigSection(
        Duration challengeTimeout,
        DisconnectConfig disconnect
) {
    public static final Set<String> SUPPORTED_ACTIONS = Set.of("broadcast", "notify");

    public record DisconnectConfig(
            Duration combatLogWindow,
            Duration reconnectGracePeriod,
            String action
    ) {
        public DisconnectConfig {
            Objects.requireNonNull(combatLogWindow, "combatLogWindow must not be null");
            Objects.requireNonNull(reconnectGracePeriod, "reconnectGracePeriod must not be null");
            Objects.requireNonNull(action, "action must not be null");
        }
    }

    public DuelConfigSection {
        Objects.requireNonNull(challengeTimeout, "challengeTimeout must not be null");
        Objects.requireNonNull(disconnect, "disconnect must not be null");
    }

    public static DuelConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("duel");
        if (section == null) {
            throw new ConfigValidationException("duel", "Missing required configuration section 'duel'");
        }

        String timeoutKey = "duel.challenge-timeout";
        if (!section.contains("challenge-timeout")) {
            throw new ConfigValidationException(timeoutKey, "Missing required key: " + timeoutKey);
        }
        Duration challengeTimeout = DurationParser.parsePositive(section.getString("challenge-timeout"), timeoutKey);

        ConfigurationSection discSection = section.getConfigurationSection("disconnect");
        if (discSection == null) {
            throw new ConfigValidationException("duel.disconnect", "Missing required configuration section 'duel.disconnect'");
        }

        String combatLogKey = "duel.disconnect.combat-log-window";
        if (!discSection.contains("combat-log-window")) {
            throw new ConfigValidationException(combatLogKey, "Missing required key: " + combatLogKey);
        }
        Duration combatLogWindow = DurationParser.parseNonNegative(discSection.getString("combat-log-window"), combatLogKey);

        String graceKey = "duel.disconnect.reconnect-grace-period";
        if (!discSection.contains("reconnect-grace-period")) {
            throw new ConfigValidationException(graceKey, "Missing required key: " + graceKey);
        }
        Duration reconnectGracePeriod = DurationParser.parseNonNegative(discSection.getString("reconnect-grace-period"), graceKey);

        String actionKey = "duel.disconnect.action";
        if (!discSection.contains("action")) {
            throw new ConfigValidationException(actionKey, "Missing required key: " + actionKey);
        }
        String actionRaw = discSection.getString("action");
        if (actionRaw == null || actionRaw.isBlank()) {
            throw new ConfigValidationException(actionKey, "Configuration key '" + actionKey + "' must not be blank");
        }
        String action = actionRaw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new ConfigValidationException(actionKey, "Unsupported disconnect action '" + actionRaw + "'. Supported actions: " + SUPPORTED_ACTIONS);
        }

        return new DuelConfigSection(challengeTimeout, new DisconnectConfig(combatLogWindow, reconnectGracePeriod, action));
    }
}
