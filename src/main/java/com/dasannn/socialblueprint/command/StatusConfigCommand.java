package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.ConfigValidationException;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Handles `/status config <key> [value]` per T-035.
 * Reads, validates, persists, and publishes configuration updates in-game.
 * Refuses invalid values with the same message as startup validation.
 * No player-visible string is hardcoded in Java (T-032).
 */
public class StatusConfigCommand {

    private static final List<String> SUGGESTED_KEYS = List.of(
            "reload",
            "language",
            "chat-prefix",
            "confidence.half-life",
            "confidence.low-threshold",
            "confidence.established-threshold",
            "confidence.high-threshold",
            "psychosis.window",
            "psychosis.medium-threshold",
            "psychosis.high-threshold",
            "psychosis.extreme-threshold",
            "honor.cost",
            "honor.multiplier-window",
            "honor.cap-window",
            "honor.cooldown-per-pair",
            "honor.max-per-target",
            "tiers.tier-4.prefix",
            "tiers.tier-4.threshold",
            "tiers.tier-3.prefix",
            "tiers.tier-3.threshold",
            "tiers.tier-2.prefix",
            "tiers.tier-2.threshold",
            "tiers.tier-1.prefix",
            "tiers.tier-1.threshold",
            "tiers.tier0.prefix",
            "tiers.tier0.threshold",
            "tiers.tier1.prefix",
            "tiers.tier1.threshold",
            "tiers.tier2.prefix",
            "tiers.tier2.threshold",
            "tiers.tier3.prefix",
            "tiers.tier3.threshold",
            "tiers.tier4.prefix",
            "tiers.tier4.threshold"
    );

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final com.dasannn.socialblueprint.storage.AuditRepository auditRepository;

    public StatusConfigCommand(ConfigManager configManager, MessageRegistry messageRegistry, com.dasannn.socialblueprint.storage.AuditRepository auditRepository) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.auditRepository = auditRepository;
    }

    public StatusConfigCommand(ConfigManager configManager, MessageRegistry messageRegistry) {
        this(configManager, messageRegistry, null);
    }

    public boolean execute(CommandSender sender, String[] args) {
        return execute(sender, args, configManager.snapshot());
    }

    public boolean execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Permission check
        if (!PermissionChecker.hasPermission(sender, "admin-config", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
            return true;
        }

        String sub = args[0];

        // Reload command: /status config reload
        if (args.length == 1 && "reload".equalsIgnoreCase(sub)) {
            try {
                RuntimeSnapshot reloadedSnapshot = configManager.reload();
                sender.sendMessage(messageRegistry.renderWithPrefix(reloadedSnapshot, "commands.config.reload-success"));
            } catch (ConfigValidationException e) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                        Map.of("key", e.key(), "error", e.getMessage())));
            } catch (Exception e) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                        Map.of("key", "reload", "error", e.getMessage() != null ? e.getMessage() : "")));
            }
            return true;
        }

        String key;
        String rawValue = null;

        if ("set".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
                return true;
            }
            key = args[1];
            rawValue = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        } else if ("get".equalsIgnoreCase(sub)) {
            if (args.length != 2) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
                return true;
            }
            key = args[1];
        } else if (args.length == 1) {
            key = args[0];
        } else {
            key = args[0];
            rawValue = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        // Get key value: /status config [get] <key>
        if (rawValue == null) {
            try {
                String value = configManager.get(snapshot, key);
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.get",
                        Map.of("key", key, "value", value)));
            } catch (ConfigValidationException e) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.invalid-key",
                        Map.of("key", key)));
            }
            return true;
        }

        // Set key value: /status config [set] <key> <value>
        if (!configManager.isEditableKey(snapshot, key)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.invalid-key",
                    Map.of("key", key)));
            return true;
        }

        try {
            String oldValue = "";
            try {
                oldValue = configManager.get(snapshot, key);
            } catch (Exception ignored) {
            }
            RuntimeSnapshot updatedSnapshot = configManager.set(key, rawValue);
            String newValue = configManager.get(updatedSnapshot, key);
            if (auditRepository != null) {
                com.dasannn.socialblueprint.domain.PlayerId actor = (sender instanceof org.bukkit.entity.Player p)
                        ? com.dasannn.socialblueprint.domain.PlayerId.of(p.getUniqueId())
                        : com.dasannn.socialblueprint.domain.PlayerId.CONSOLE;
                auditRepository.saveAsync(com.dasannn.socialblueprint.domain.AuditEvent.forConfigKey(
                        actor, "config_set", key, oldValue, newValue, java.time.Instant.now()
                ));
            }
            sender.sendMessage(messageRegistry.renderWithPrefix(updatedSnapshot, "commands.config.set-success",
                    Map.of("key", key, "value", rawValue)));
        } catch (ConfigValidationException e) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                    Map.of("key", e.key(), "error", e.getMessage())));
        } catch (Exception e) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                    Map.of("key", key, "error", e.getMessage() != null ? e.getMessage() : "")));
        }

        return true;
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        return tabComplete(sender, args, configManager.snapshot());
    }

    public List<String> tabComplete(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!PermissionChecker.hasPermission(sender, "admin-config", snapshot)) {
            return List.of();
        }

        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(SUGGESTED_KEYS);
            suggestions.add(0, "set");
            suggestions.add(0, "get");
            List<String> matches = new ArrayList<>();
            for (String s : suggestions) {
                if (s.toLowerCase(Locale.ROOT).startsWith(current)) {
                    matches.add(s);
                }
            }
            return matches;
        }

        if (args.length == 2 && ("set".equalsIgnoreCase(args[0]) || "get".equalsIgnoreCase(args[0]))) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String key : SUGGESTED_KEYS) {
                if (key.toLowerCase(Locale.ROOT).startsWith(current)) {
                    matches.add(key);
                }
            }
            return matches;
        }

        if (args.length == 2 && "language".equalsIgnoreCase(args[0])) {
            return List.of("en", "es");
        }

        if (args.length == 3 && "set".equalsIgnoreCase(args[0]) && "language".equalsIgnoreCase(args[1])) {
            return List.of("en", "es");
        }

        return List.of();
    }
}
