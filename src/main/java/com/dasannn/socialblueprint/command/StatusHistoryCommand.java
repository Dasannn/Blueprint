package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles `/status history [player]` with date, signed delta and reason per SB-085.
 */
public class StatusHistoryCommand {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final ProfileService profileService;
    private final ReputationRepository reputationRepository;
    private final MessageRegistry messageRegistry;
    private final Consumer<Runnable> mainThreadRunner;

    public StatusHistoryCommand(
            ProfileService profileService,
            ReputationRepository reputationRepository,
            MessageRegistry messageRegistry,
            Consumer<Runnable> mainThreadRunner
    ) {
        this.profileService = profileService;
        this.reputationRepository = reputationRepository;
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (sender instanceof Player && !PermissionChecker.hasPermission(sender, "admin-adjust", snapshot)
                && !PermissionChecker.hasPermission(sender, "admin-revoke", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        // 1. Determine target
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
                return CompletableFuture.completedFuture(null);
            }
            return showHistory(sender, player.getName(), snapshot);
        }

        // Target specified
        return showHistory(sender, args[0], snapshot);
    }

    private CompletableFuture<Void> showHistory(CommandSender sender, String targetInput, RuntimeSnapshot snapshot) {
        if (profileService == null || reputationRepository == null) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found", Map.of("player", targetInput)));
            return CompletableFuture.completedFuture(null);
        }

        return profileService.resolveTargetIdentityAsync(targetInput)
                .thenCompose(optIdentity -> {
                    if (optIdentity.isEmpty()) {
                        mainThreadRunner.accept(() -> sender.sendMessage(
                                messageRegistry.renderWithPrefix(snapshot, "status.not-found", Map.of("player", targetInput))
                        ));
                        return CompletableFuture.completedFuture(null);
                    }

                    ProfileService.TargetIdentity target = optIdentity.get();
                    return reputationRepository.findByTargetAsync(target.id())
                            .thenAccept(events -> mainThreadRunner.accept(
                                    () -> renderHistory(sender, target.name(), events, snapshot)));
                });
    }

    private void renderHistory(
            CommandSender sender,
            String targetName,
            List<ReputationEvent> events,
            RuntimeSnapshot snapshot
    ) {
        sender.sendMessage(messageRegistry.render(snapshot, "status.history-header", Map.of("player", targetName)));

        if (events == null || events.isEmpty()) {
            sender.sendMessage(messageRegistry.render(snapshot, "status.history-empty"));
            return;
        }

        for (ReputationEvent event : events) {
            if (event.kind() == com.dasannn.socialblueprint.domain.HonorKind.REVOCATION) continue;
            if (PermissionChecker.hasPermission(sender, "admin-revoke", snapshot)
                    || PermissionChecker.hasPermission(sender, "admin-adjust", snapshot))
                sender.sendMessage(messageRegistry.render(snapshot, "honor.rating-id", Map.of("id", String.valueOf(event.id()))));
            if (event.revokedBy() != null)
                sender.sendMessage(messageRegistry.render(snapshot, "honor.revoked", Map.of("admin", event.revokedBy())));
            String timeStr = DATE_FORMATTER.format(event.createdAt());

            Component deltaComp;
            if (event.delta() > 0) {
                deltaComp = Component.text("+" + event.delta(), NamedTextColor.GREEN);
            } else if (event.delta() < 0) {
                deltaComp = Component.text(String.valueOf(event.delta()), NamedTextColor.RED);
            } else {
                deltaComp = Component.text("0", NamedTextColor.GRAY);
            }

            String reasonStr;
            String rawReason = event.reason();
            if (rawReason == null || rawReason.isBlank()) {
                reasonStr = "-";
            } else if (event.kind() == com.dasannn.socialblueprint.domain.HonorKind.SYSTEM_KILL && snapshot.messages().isKnownKey(rawReason)) {
                // Translated from the message key rather than printed raw (T-134)
                reasonStr = messageRegistry.getRaw(snapshot, rawReason);
            } else {
                reasonStr = rawReason;
            }

            reasonStr = snapshot.config().chatFilter().apply(com.dasannn.socialblueprint.domain.CommentSanitizer.toPlainText(reasonStr),
                    messageRegistry.getRaw(snapshot, "chat-filter.replacement"));
            sender.sendMessage(messageRegistry.render(
                    snapshot,
                    "status.history-entry",
                    Map.of(
                            "time", timeStr,
                            "reason", reasonStr
                    ),
                    Map.of(
                            "delta", deltaComp
                    )
            ));
        }
    }

}
