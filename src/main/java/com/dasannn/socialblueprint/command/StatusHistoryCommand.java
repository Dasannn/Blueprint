package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.gui.StatusGuiService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles `/status history [player]` per T-134 and SB-082.
 * Renders reputation events with delta as a styled component, and hides rater identity
 * behind anonymous form unless revealed through Vault per SB-082.
 */
public class StatusHistoryCommand {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final ProfileService profileService;
    private final ReputationRepository reputationRepository;
    private final MessageRegistry messageRegistry;
    private final Consumer<Runnable> mainThreadRunner;
    private final RaterRevealRepository raterRevealRepository;
    private final StatusGuiService statusGuiService;
    private final Function<UUID, String> raterNameResolver;

    public StatusHistoryCommand(
            ProfileService profileService,
            ReputationRepository reputationRepository,
            MessageRegistry messageRegistry,
            Consumer<Runnable> mainThreadRunner
    ) {
        this(profileService, reputationRepository, messageRegistry, mainThreadRunner, null, null, null);
    }

    public StatusHistoryCommand(
            ProfileService profileService,
            ReputationRepository reputationRepository,
            MessageRegistry messageRegistry,
            Consumer<Runnable> mainThreadRunner,
            RaterRevealRepository raterRevealRepository,
            StatusGuiService statusGuiService
    ) {
        this(profileService, reputationRepository, messageRegistry, mainThreadRunner, raterRevealRepository, statusGuiService, null);
    }

    public StatusHistoryCommand(
            ProfileService profileService,
            ReputationRepository reputationRepository,
            MessageRegistry messageRegistry,
            Consumer<Runnable> mainThreadRunner,
            RaterRevealRepository raterRevealRepository,
            StatusGuiService statusGuiService,
            Function<UUID, String> raterNameResolver
    ) {
        this.profileService = profileService;
        this.reputationRepository = reputationRepository;
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.statusGuiService = statusGuiService;
        this.raterRevealRepository = raterRevealRepository != null
                ? raterRevealRepository
                : (statusGuiService != null ? statusGuiService.raterRevealRepository() : null);
        this.raterNameResolver = raterNameResolver;
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
                    CompletableFuture<List<ReputationEvent>> eventsFuture = reputationRepository.findByTargetAsync(target.id());

                    UUID viewerUuid = (sender instanceof Player player) ? player.getUniqueId() : null;
                    CompletableFuture<Set<Long>> revealsFuture = (viewerUuid != null && raterRevealRepository != null)
                            ? raterRevealRepository.findRevealedEventsByViewerAsync(viewerUuid)
                            : CompletableFuture.completedFuture(Collections.emptySet());

                    return CompletableFuture.allOf(eventsFuture, revealsFuture)
                            .thenAccept(v -> {
                                List<ReputationEvent> events = eventsFuture.join();
                                Set<Long> reveals = revealsFuture.join();
                                mainThreadRunner.accept(() -> renderHistory(sender, target.name(), events, reveals, snapshot));
                            });
                });
    }

    private void renderHistory(
            CommandSender sender,
            String targetName,
            List<ReputationEvent> events,
            Set<Long> reveals,
            RuntimeSnapshot snapshot
    ) {
        sender.sendMessage(messageRegistry.render(snapshot, "status.history-header", Map.of("player", targetName)));

        if (events == null || events.isEmpty()) {
            sender.sendMessage(messageRegistry.render(snapshot, "status.history-empty"));
            return;
        }

        for (ReputationEvent event : events) {
            String timeStr = TIME_FORMATTER.format(event.createdAt());

            Component deltaComp;
            if (event.delta() > 0) {
                deltaComp = Component.text("+" + event.delta(), NamedTextColor.GREEN);
            } else if (event.delta() < 0) {
                deltaComp = Component.text(String.valueOf(event.delta()), NamedTextColor.RED);
            } else {
                deltaComp = Component.text("0", NamedTextColor.GRAY);
            }

            String actorStr;
            if (event.actor() == null || event.kind() == HonorKind.SYSTEM_KILL) {
                String localizedActor = messageRegistry.getRaw(snapshot, "status.system-actor");
                actorStr = (localizedActor != null && !localizedActor.isBlank() && !localizedActor.startsWith("!"))
                        ? localizedActor
                        : "System";
            } else {
                UUID raterUuid = event.actor().uuid();
                boolean revealed = (statusGuiService != null)
                        ? statusGuiService.isRaterRevealed(sender, event, snapshot, reveals)
                        : StatusGuiService.isRaterRevealedStatic(sender, event.id(), raterUuid, snapshot, reveals);

                if (revealed) {
                    actorStr = resolveRaterName(raterUuid, snapshot);
                } else {
                    String anonRaw = messageRegistry.getRaw(snapshot, "gui.history.anonymous-rater");
                    actorStr = (anonRaw != null && !anonRaw.isBlank() && !anonRaw.startsWith("!"))
                            ? stripColorCodes(anonRaw)
                            : "Anonymous";
                }
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

            sender.sendMessage(messageRegistry.render(
                    snapshot,
                    "status.history-entry",
                    Map.of(
                            "time", timeStr,
                            "actor", actorStr,
                            "reason", reasonStr
                    ),
                    Map.of(
                            "delta", deltaComp
                    )
            ));
        }
    }

    private String resolveRaterName(UUID raterUuid, RuntimeSnapshot snapshot) {
        if (raterNameResolver != null) {
            String name = raterNameResolver.apply(raterUuid);
            if (name != null && !name.isBlank()) {
                return stripColorCodes(name);
            }
        }
        if (statusGuiService != null) {
            return stripColorCodes(statusGuiService.resolveRaterName(raterUuid, snapshot));
        }
        if (raterUuid == null) {
            String anon = messageRegistry.getRaw(snapshot, "gui.history.anonymous-rater");
            return (anon != null && !anon.isBlank() && !anon.startsWith("!")) ? stripColorCodes(anon) : "Anonymous";
        }
        try {
            if (org.bukkit.Bukkit.getServer() != null) {
                org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(raterUuid);
                if (op != null && op.getName() != null && !op.getName().isBlank()) {
                    return stripColorCodes(op.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return raterUuid.toString();
    }

    private static String stripColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("&[0-9a-fA-FK-ORk-or]|&#[0-9a-fA-F]{6}", "");
    }
}
