package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.feature.update.UpdateService;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Handles `/status update` per SB-071, SB-072, SB-074, and T-082.
 * Downloads the release asset, verifies its checksum before writing anything,
 * and writes into plugins/update/.
 */
public class StatusUpdateCommand {

    private final UpdateService updateService;
    private final MessageRegistry messageRegistry;

    public StatusUpdateCommand(UpdateService updateService, MessageRegistry messageRegistry) {
        this.updateService = Objects.requireNonNull(updateService, "updateService must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
    }

    public CompletableFuture<Boolean> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!PermissionChecker.hasPermission(sender, "admin-update", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(false);
        }

        if (args != null && args.length == 1 && "check".equalsIgnoreCase(args[0])) {
            return updateService.checkForUpdateAsync(snapshot).thenApply(result -> {
                updateService.reportVersion(sender, snapshot, result);
                return false;
            });
        }

        // Dispatch download, checksum verification, and write on async executor
        return updateService.downloadUpdateAsync(sender, snapshot);
    }
}
