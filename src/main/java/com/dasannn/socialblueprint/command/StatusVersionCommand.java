package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.feature.update.UpdateService;
import com.dasannn.socialblueprint.feature.update.VersionCheckResult;
import com.dasannn.socialblueprint.feature.update.VersionComparison;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Handles `/status version` per SB-070 and T-081.
 * Reports running version against the latest release.
 * Honest: says "unknown" rather than guessing when the check has not completed or failed.
 */
public class StatusVersionCommand {

    private final UpdateService updateService;
    private final MessageRegistry messageRegistry;

    public StatusVersionCommand(UpdateService updateService, MessageRegistry messageRegistry) {
        this.updateService = Objects.requireNonNull(updateService, "updateService must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!PermissionChecker.hasPermission(sender, "version", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        String runningVersion = updateService.getCurrentVersion();
        VersionCheckResult check = updateService.getLastCheckResult();

        if (check == null || check.comparison() == VersionComparison.UNKNOWN) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "updater.version-unknown",
                    Map.of("current", runningVersion)));
            // Retry on every unknown, not only before the first check: a failed
            // startup check (no release yet, network down) otherwise stuck the
            // answer at unknown until a restart.
            updateService.checkForUpdateAsync();
            return CompletableFuture.completedFuture(null);
        }

        switch (check.comparison()) {
            case UP_TO_DATE -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "updater.version-current",
                    Map.of("current", runningVersion, "latest", check.latestVersion())));
            case OUTDATED -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "updater.version-outdated",
                    Map.of("current", runningVersion, "latest", check.latestVersion())));
            case AHEAD -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "updater.version-ahead",
                    Map.of("current", runningVersion, "latest", check.latestVersion())));
            default -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "updater.version-unknown",
                    Map.of("current", runningVersion)));
        }

        return CompletableFuture.completedFuture(null);
    }
}
