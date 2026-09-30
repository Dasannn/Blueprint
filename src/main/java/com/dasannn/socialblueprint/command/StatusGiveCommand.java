package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Handles giving honor (`/status give <player> [reason]` and alias `/status trust`)
 * per SB-050, SB-052, and SB-055.
 * Only players can issue honor. Shows exact cost and registers pending confirmation.
 */
public class StatusGiveCommand {

    private final HonorService honorService;
    private final MessageRegistry messageRegistry;

    public StatusGiveCommand(HonorService honorService, MessageRegistry messageRegistry) {
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

        if (args.length < 1) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.give-usage"));
            return CompletableFuture.completedFuture(null);
        }

        String target = args[0];
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
        if (reason != null && reason.length() > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_REASON_LENGTH) {
            reason = reason.substring(0, com.dasannn.socialblueprint.domain.ReputationEvent.MAX_REASON_LENGTH);
        }

        return honorService.preparePlayerHonor(player, target, HonorKind.POSITIVE, reason, snapshot);
    }
}
