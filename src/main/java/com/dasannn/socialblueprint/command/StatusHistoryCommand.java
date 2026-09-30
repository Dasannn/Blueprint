package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles `/status history [player]` per T-134.
 * Renders reputation events with their reason translated from the message key.
 */
public class StatusHistoryCommand {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;

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

        // 1. Determine target
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
                return CompletableFuture.completedFuture(null);
            }
            if (!PermissionChecker.hasPermission(player, "show", snapshot)
                    && !PermissionChecker.hasPermission(player, "view-reputation", snapshot)) {
                player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return CompletableFuture.completedFuture(null);
            }
            return showHistory(sender, player.getName(), snapshot);
        }

        // Target specified
        if (!PermissionChecker.hasPermission(sender, "show-others", snapshot)
                && !PermissionChecker.hasPermission(sender, "view-reputation", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

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
                            .thenAccept(events -> mainThreadRunner.accept(() -> renderHistory(sender, target.name(), events, snapshot)));
                });
    }

    private void renderHistory(CommandSender sender, String targetName, List<ReputationEvent> events, RuntimeSnapshot snapshot) {
        sender.sendMessage(messageRegistry.render(snapshot, "status.history-header", Map.of("player", targetName)));

        if (events == null || events.isEmpty()) {
            sender.sendMessage(messageRegistry.render(snapshot, "status.history-empty"));
            return;
        }

        for (ReputationEvent event : events) {
            String timeStr = TIME_FORMATTER.format(event.createdAt());

            String deltaStr = event.delta() > 0 ? "&a+" + event.delta()
                    : event.delta() < 0 ? "&c" + event.delta()
                    : "&70";

            String actorStr;
            if (event.actor() == null) {
                String localizedActor = messageRegistry.getRaw(snapshot, "status.system-actor");
                actorStr = (localizedActor != null && !localizedActor.isBlank() && !localizedActor.startsWith("!"))
                        ? localizedActor
                        : "System";
            } else {
                actorStr = event.actor().toString();
            }

            String reasonStr;
            String rawReason = event.reason();
            if (rawReason == null || rawReason.isBlank()) {
                reasonStr = "-";
            } else if (snapshot.messages().isKnownKey(rawReason)) {
                // Translated from the message key rather than printed raw (T-134)
                reasonStr = messageRegistry.getRaw(snapshot, rawReason);
            } else {
                reasonStr = rawReason;
            }

            sender.sendMessage(messageRegistry.render(snapshot, "status.history-entry", Map.of(
                    "time", timeStr,
                    "delta", deltaStr,
                    "actor", actorStr,
                    "reason", reasonStr
            )));
        }
    }
}
