package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Handles `/status admin give|take|reset` per SB-058, T-055, and T-056.
 * Free, no cooldown, no cap, audited.
 * Centralised argument parsing ensures no uncaught NumberFormatException is thrown.
 */
public class StatusAdminCommand {

    private final HonorService honorService;
    private final MessageRegistry messageRegistry;
    private final com.dasannn.socialblueprint.feature.legacy.LegacyImportService legacyImportService;

    public StatusAdminCommand(HonorService honorService, MessageRegistry messageRegistry) {
        this(honorService, messageRegistry, null);
    }

    public StatusAdminCommand(
            HonorService honorService,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.feature.legacy.LegacyImportService legacyImportService
    ) {
        // Nullable: /status admin import needs no economy, so this command is also
        // constructed on a server wired for the import alone. The give/take/reset
        // branches check before use.
        this.honorService = honorService;
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.legacyImportService = legacyImportService;
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (args.length < 1) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.usage"));
            return CompletableFuture.completedFuture(null);
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if ("import".equals(action)) {
            if (!PermissionChecker.hasPermission(sender, "admin-import", snapshot)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return CompletableFuture.completedFuture(null);
            }
            if (legacyImportService == null) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return CompletableFuture.completedFuture(null);
            }
            String filePath = args.length >= 2 ? args[1] : null;
            return legacyImportService.importLegacyAsync(sender, filePath, snapshot);
        }

        if (honorService == null) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.usage"));
            return CompletableFuture.completedFuture(null);
        }

        // Permission check for give/take/reset
        if (!PermissionChecker.hasPermission(sender, "admin-adjust", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        if (args.length < 2) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.usage"));
            return CompletableFuture.completedFuture(null);
        }

        String target = args[1];

        switch (action) {
            case "give" -> {
                int amount = 1;
                if (args.length >= 3) {
                    OptionalInt parsed = ArgumentParser.parseInt(args[2]);
                    if (parsed.isEmpty()) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.invalid-integer",
                                Map.of("value", args[2])));
                        return CompletableFuture.completedFuture(null);
                    }
                    int val = parsed.getAsInt();
                    if (val <= 0 || val > ReputationEvent.MAX_DELTA) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.invalid-amount",
                                Map.of("max", String.valueOf(ReputationEvent.MAX_DELTA))));
                        return CompletableFuture.completedFuture(null);
                    }
                    amount = val;
                }
                return honorService.adminGive(sender, target, amount, snapshot);
            }
            case "take" -> {
                int amount = 1;
                if (args.length >= 3) {
                    OptionalInt parsed = ArgumentParser.parseInt(args[2]);
                    if (parsed.isEmpty()) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.invalid-integer",
                                Map.of("value", args[2])));
                        return CompletableFuture.completedFuture(null);
                    }
                    int val = parsed.getAsInt();
                    if (val <= 0 || val > ReputationEvent.MAX_DELTA) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.invalid-amount",
                                Map.of("max", String.valueOf(ReputationEvent.MAX_DELTA))));
                        return CompletableFuture.completedFuture(null);
                    }
                    amount = val;
                }
                return honorService.adminTake(sender, target, amount, snapshot);
            }
            case "reset" -> {
                return honorService.adminReset(sender, target, snapshot);
            }
            default -> {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.usage"));
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        boolean canAdjust = PermissionChecker.hasPermission(sender, "admin-adjust", snapshot);
        boolean canImport = PermissionChecker.hasPermission(sender, "admin-import", snapshot);

        if (!canAdjust && !canImport) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            List<String> actions = new ArrayList<>();
            if (canAdjust) {
                actions.add("give");
                actions.add("take");
                actions.add("reset");
            }
            if (canImport) {
                actions.add("import");
            }
            List<String> matches = new ArrayList<>();
            for (String a : actions) {
                if (a.startsWith(current)) {
                    matches.add(a);
                }
            }
            return matches;
        }

        return Collections.emptyList();
    }
}
