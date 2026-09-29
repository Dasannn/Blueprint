package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Handles `/status confirm` per SB-052 and SB-057.
 * Confirms a pending honor action, charges the actor, and commits the reputation event.
 */
public class StatusConfirmCommand {

    private final HonorService honorService;
    private final MessageRegistry messageRegistry;

    public StatusConfirmCommand(HonorService honorService, MessageRegistry messageRegistry) {
        this.honorService = Objects.requireNonNull(honorService, "honorService must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Resolve sender before dispatch (T-050)
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
            return CompletableFuture.completedFuture(null);
        }

        return honorService.confirmPlayerHonor(player, snapshot);
    }
}
