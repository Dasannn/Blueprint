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

        if ("honor.percent".equals(key)) key = "honor.cost-percent";
        if (!configManager.isEditableKey(snapshot, key)) {
            List<String> matches = suffixMatches(configManager.editableKeys(snapshot), key);
            if (matches.size() > 1) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "config-suffix.ambiguous",
                        Map.of("key", key, "keys", String.join(", ", matches.stream().limit(8).toList()))));
                return CompletableFuture.completedFuture(null);
            }
            if (matches.size() == 1) key = matches.getFirst();
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

        return setValueAsync(sender, key, rawValue, snapshot);
    }

    /** Feature clicks use the same writer and audit path, with their own permission. */
    public CompletableFuture<Void> toggleFeatureAsync(CommandSender sender, String key, RuntimeSnapshot snapshot) {
        if (!PermissionChecker.hasPermission(sender, "admin-features", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }
        String value = snapshot.getLeaf(key);
        if (!com.dasannn.socialblueprint.feature.gui.FeatureSwitchLayout.keys().contains(key)
                || !("true".equals(value) || "false".equals(value))) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "features.unavailable"));
            return CompletableFuture.completedFuture(null);
        }
        return setValueAsync(sender, key, String.valueOf(!Boolean.parseBoolean(value)), snapshot);
    }

    private CompletableFuture<Void> setValueAsync(CommandSender sender, String key, String rawValue, RuntimeSnapshot snapshot) {
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
            List<String> keys = new ArrayList<>(List.of("get", "set", "reload"));
            keys.addAll(keySuggestions(snapshot));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return keys.stream().filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
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
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return keySuggestions(snapshot).stream().filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }

        return List.of();
    }

    static List<String> suffixMatches(List<String> keys, String suffix) {
        return keys.stream().filter(key -> key.equals(suffix) || key.endsWith("." + suffix)).toList();
    }

    static String shortestSuffix(List<String> keys, String key) {
        String[] parts = key.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            String suffix = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
            if (suffixMatches(keys, suffix).size() == 1) return suffix;
        }
        return key;
    }

    private List<String> keySuggestions(RuntimeSnapshot snapshot) {
        List<String> keys = configManager.editableKeys(snapshot);
        var suggestions = new java.util.LinkedHashSet<String>();
        keys.forEach(key -> suggestions.add(shortestSuffix(keys, key)));
        suggestions.addAll(keys);
        suggestions.add("honor.percent");
        return List.copyOf(suggestions);
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
