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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Handles `/status config <key> [value]` per T-035.
 * Reads, validates, persists, and publishes configuration updates in-game.
 * Refuses invalid values with the same message as startup validation.
 * No player-visible string is hardcoded in Java (T-032).
 */
public class StatusConfigCommand {

    private static final List<String> SUPPORTED_LANGUAGES = List.of("en", "es");

    private static final List<String> SUGGESTED_KEYS = List.of(
            "reload",
            "language",
            "chat-prefix",
            "confidence.half-life",
            "confidence.low-threshold",
            "confidence.established-threshold",
            "confidence.high-threshold",
            "decay.enabled",
            "decay.half-life",
            "decay.floor",
            "decay.cache-ttl",
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
            "tiers.tier4.threshold",
            "duel.challenge-timeout",
            "duel.disconnect.combat-log-window",
            "duel.disconnect.reconnect-grace-period",
            "duel.disconnect.action",
            "permissions.show",
            "permissions.show-others",
            "permissions.give-reputation",
            "permissions.take-reputation",
            "permissions.view-reputation",
            "permissions.admin-adjust",
            "permissions.admin-config",
            "permissions.duel",
            "permissions.version",
            "permissions.admin-update",
            "permissions.admin-import",
            "effects.check-interval",
            "effects.quiet-interval.medium",
            "effects.quiet-interval.high",
            "effects.quiet-interval.extreme",
            "effects.max-episode-ticks",
            "legacy-import.trust-name-lookup",
            "update.check-on-startup",
            "update.auto-download",
            "update.repository",
            "update.channel",
            "update.api-url",
            "update.max-download-bytes",
            "kill-penalty.delta",
            "kill-penalty.pair-cooldown",
            "kill-penalty.cap-window",
            "kill-penalty.max-loss",
            "kill-penalty.exempt-worlds",
            "sounds.creeper-fuse.key",
            "sounds.creeper-fuse.volume",
            "sounds.creeper-fuse.pitch",
            "sounds.creeper-fuse.category",
            "history.reveal-cost"
    );

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final com.dasannn.socialblueprint.storage.AuditRepository auditRepository;
    private final Consumer<Runnable> mainThreadRunner;
    private final Logger logger;

    public StatusConfigCommand(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.storage.AuditRepository auditRepository,
            Consumer<Runnable> mainThreadRunner,
            Logger logger
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.auditRepository = auditRepository;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.logger = logger != null ? logger : Logger.getLogger(StatusConfigCommand.class.getName());
    }

    public StatusConfigCommand(ConfigManager configManager, MessageRegistry messageRegistry, com.dasannn.socialblueprint.storage.AuditRepository auditRepository) {
        this(configManager, messageRegistry, auditRepository, Runnable::run, Logger.getLogger(StatusConfigCommand.class.getName()));
    }

    public StatusConfigCommand(ConfigManager configManager, MessageRegistry messageRegistry) {
        this(configManager, messageRegistry, null);
    }

    public boolean execute(CommandSender sender, String[] args) {
        return execute(sender, args, configManager.snapshot());
    }

    public boolean execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        executeAsync(sender, args, snapshot).join();
        return true;
    }

    public CompletableFuture<Void> executeAsync(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Permission check
        if (!PermissionChecker.hasPermission(sender, "admin-config", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        if (args.length == 0) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
            return CompletableFuture.completedFuture(null);
        }

        String sub = args[0];

        // Reload command: /status config reload
        if (args.length == 1 && "reload".equalsIgnoreCase(sub)) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            configManager.reloadAsync().whenComplete((reloadedSnapshot, ex) -> {
                mainThreadRunner.accept(() -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        if (cause instanceof ConfigValidationException cve) {
                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                                    Map.of("key", cve.key(), "error", cve.getMessage())));
                        } else {
                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                                    Map.of("key", "reload", "error", cause.getMessage() != null ? cause.getMessage() : "")));
                        }
                    } else {
                        sender.sendMessage(messageRegistry.renderWithPrefix(reloadedSnapshot, "commands.config.reload-success"));
                    }
                    future.complete(null);
                });
            });
            return future;
        }

        String key;
        String rawValue = null;

        if ("set".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
                return CompletableFuture.completedFuture(null);
            }
            key = args[1];
            rawValue = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        } else if ("get".equalsIgnoreCase(sub)) {
            if (args.length != 2) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
                return CompletableFuture.completedFuture(null);
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
            return CompletableFuture.completedFuture(null);
        }

        // Set key value: /status config [set] <key> <value>
        if (!configManager.isEditableKey(snapshot, key)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.invalid-key",
                    Map.of("key", key)));
            return CompletableFuture.completedFuture(null);
        }

        String oldValue = "";
        try {
            oldValue = configManager.get(snapshot, key);
        } catch (Exception ignored) {
        }
        final String finalOldValue = oldValue;
        final String finalKey = key;
        final String finalRawValue = rawValue;
        final com.dasannn.socialblueprint.domain.PlayerId actor = (sender instanceof org.bukkit.entity.Player p)
                ? com.dasannn.socialblueprint.domain.PlayerId.of(p.getUniqueId())
                : com.dasannn.socialblueprint.domain.PlayerId.CONSOLE;

        CompletableFuture<Void> future = new CompletableFuture<>();

        // Main-thread file I/O stays forbidden: persist and reload on ioExecutor
        configManager.setAsync(finalKey, finalRawValue).whenComplete((updatedSnapshot, setEx) -> {
            if (setEx != null) {
                mainThreadRunner.accept(() -> {
                    Throwable cause = setEx instanceof java.util.concurrent.CompletionException ? setEx.getCause() : setEx;
                    if (cause instanceof ConfigValidationException cve) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                                Map.of("key", cve.key(), "error", cve.getMessage())));
                    } else {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.set-failed",
                                Map.of("key", finalKey, "error", cause.getMessage() != null ? cause.getMessage() : "")));
                    }
                    future.complete(null);
                });
                return;
            }

            String newValue = configManager.get(updatedSnapshot, finalKey);

            if (auditRepository != null) {
                auditRepository.saveAsync(com.dasannn.socialblueprint.domain.AuditEvent.forConfigKey(
                        actor, "config_set", finalKey, finalOldValue, newValue, java.time.Instant.now()
                )).whenComplete((audit, auditEx) -> {
                    mainThreadRunner.accept(() -> {
                        if (auditEx == null) {
                            sender.sendMessage(messageRegistry.renderWithPrefix(updatedSnapshot, "commands.config.set-success",
                                    Map.of("key", finalKey, "value", finalRawValue)));
                        } else {
                            // Do not roll back configuration. Say plainly in reply and log it.
                            logger.severe("[SocialBlueprint] Configuration update for '" + finalKey + "' was applied, but audit logging failed: " + auditEx.getMessage());
                            sender.sendMessage(messageRegistry.renderWithPrefix(updatedSnapshot, "commands.config.set-failed",
                                    Map.of("key", finalKey, "error", "Configuration applied, but audit logging failed: " + auditEx.getMessage())));
                        }
                        future.complete(null);
                    });
                });
            } else {
                mainThreadRunner.accept(() -> {
                    sender.sendMessage(messageRegistry.renderWithPrefix(updatedSnapshot, "commands.config.set-success",
                            Map.of("key", finalKey, "value", finalRawValue)));
                    future.complete(null);
                });
            }
        });

        return future;
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

        // `/status config <key> <value>` and `/status config set <key> <value>`
        // both end in a value, and `language` is the one key with a closed set
        // of them. Adding the set/get forms left this case falling through to
        // an empty list.
        if (args.length == 2 && "language".equalsIgnoreCase(args[0])) {
            return matchingLanguages(args[1]);
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0]) && "language".equalsIgnoreCase(args[1])) {
            return matchingLanguages(args[2]);
        }

        if (args.length == 2 && ("set".equalsIgnoreCase(args[0]) || "get".equalsIgnoreCase(args[0]))) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String s : SUGGESTED_KEYS) {
                if (!"reload".equals(s) && s.toLowerCase(Locale.ROOT).startsWith(current)) {
                    matches.add(s);
                }
            }
            return matches;
        }

        return List.of();
    }

    private static List<String> matchingLanguages(String current) {
        String prefix = current == null ? "" : current.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String lang : SUPPORTED_LANGUAGES) {
            if (lang.startsWith(prefix)) {
                matches.add(lang);
            }
        }
        return matches;
    }
}
