package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.Collection;

/**
 * Handles `/status duel <player|accept|deny|leave>` per SB-030 and T-060.
 * Centralised argument parsing and permission checks; console cannot duel (SB-065).
 */
public class StatusDuelCommand {

    private final DuelService duelService;
    private final MessageRegistry messageRegistry;
    private final PlayerLookup playerLookup;
    private final java.util.function.Supplier<Collection<? extends Player>> onlinePlayersSupplier;

    public StatusDuelCommand(
            DuelService duelService,
            MessageRegistry messageRegistry,
            java.util.function.Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this(duelService, messageRegistry, null, onlinePlayersSupplier);
    }

    public StatusDuelCommand(
            DuelService duelService,
            MessageRegistry messageRegistry,
            PlayerLookup playerLookup
    ) {
        this(duelService, messageRegistry, playerLookup, null);
    }

    public StatusDuelCommand(
            DuelService duelService,
            MessageRegistry messageRegistry,
            PlayerLookup playerLookup,
            java.util.function.Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this.duelService = Objects.requireNonNull(duelService, "duelService must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.playerLookup = playerLookup;
        this.onlinePlayersSupplier = onlinePlayersSupplier != null ? onlinePlayersSupplier : Collections::emptyList;
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
            return CompletableFuture.completedFuture(null);
        }

        if (!PermissionChecker.hasPermission(player, "duel", snapshot)) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        if (args.length == 0) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.usage"));
            return CompletableFuture.completedFuture(null);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (sub) {
            case "accept" -> executeAccept(player, subArgs, snapshot);
            case "deny" -> executeDeny(player, subArgs, snapshot);
            case "leave" -> executeLeave(player, subArgs, snapshot);
            default -> handleChallenge(player, args, snapshot);
        };
    }

    public CompletableFuture<Void> executeAccept(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
            return CompletableFuture.completedFuture(null);
        }
        if (!PermissionChecker.hasPermission(player, "duel", snapshot)) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        String target = args.length > 0 ? args[0] : null;
        DuelService.AcceptResult result = duelService.accept(PlayerId.of(player.getUniqueId()), target, snapshot);

        if (result instanceof DuelService.AcceptResult.AlreadyInDuel) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.already-in-duel"));
        } else if (result instanceof DuelService.AcceptResult.NoPendingChallenge) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.no-pending-challenge"));
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> executeDeny(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
            return CompletableFuture.completedFuture(null);
        }
        if (!PermissionChecker.hasPermission(player, "duel", snapshot)) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        String target = args.length > 0 ? args[0] : null;
        DuelService.DenyResult result = duelService.deny(PlayerId.of(player.getUniqueId()), target, snapshot);

        if (result instanceof DuelService.DenyResult.NoPendingChallenge) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.no-pending-challenge"));
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> executeLeave(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
            return CompletableFuture.completedFuture(null);
        }
        if (!PermissionChecker.hasPermission(player, "duel", snapshot)) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        DuelService.LeaveResult result = duelService.leave(PlayerId.of(player.getUniqueId()), snapshot);
        if (result instanceof DuelService.LeaveResult.NotInDuel) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.not-in-duel"));
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleChallenge(Player player, String[] args, RuntimeSnapshot snapshot) {
        PlayerId challengerId = PlayerId.of(player.getUniqueId());

        // Parse sides: check for "vs" token
        int vsIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if ("vs".equalsIgnoreCase(args[i])) {
                vsIndex = i;
                break;
            }
        }

        Map<String, Set<PlayerId>> sides = new HashMap<>();
        Set<PlayerId> side1 = new HashSet<>();
        Set<PlayerId> side2 = new HashSet<>();
        side1.add(challengerId);

        if (vsIndex >= 0) {
            // Team challenge: side 1 before 'vs', side 2 after 'vs'
            for (int i = 0; i < vsIndex; i++) {
                String name = args[i];
                Optional<PlayerId> pid = resolveOnlinePlayer(name);
                if (pid.isEmpty()) {
                    player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found", Map.of("player", name)));
                    return CompletableFuture.completedFuture(null);
                }
                side1.add(pid.get());
            }

            for (int i = vsIndex + 1; i < args.length; i++) {
                String name = args[i];
                Optional<PlayerId> pid = resolveOnlinePlayer(name);
                if (pid.isEmpty()) {
                    player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found", Map.of("player", name)));
                    return CompletableFuture.completedFuture(null);
                }
                side2.add(pid.get());
            }
        } else {
            // Standard or multi-opponent: challenger on side 1, all args on side 2
            for (String name : args) {
                Optional<PlayerId> pid = resolveOnlinePlayer(name);
                if (pid.isEmpty()) {
                    player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found", Map.of("player", name)));
                    return CompletableFuture.completedFuture(null);
                }
                side2.add(pid.get());
            }
        }

        if (side2.isEmpty()) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.usage"));
            return CompletableFuture.completedFuture(null);
        }

        // Cannot duel self
        if (side2.contains(challengerId)) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.cannot-duel-self"));
            return CompletableFuture.completedFuture(null);
        }

        sides.put("side_1", side1);
        sides.put("side_2", side2);

        DuelService.ChallengeResult result = duelService.challenge(challengerId, sides, snapshot);

        if (result instanceof DuelService.ChallengeResult.AlreadyInDuel) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.already-in-duel"));
        } else if (result instanceof DuelService.ChallengeResult.AlreadyChallenging) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.already-in-duel"));
        } else if (result instanceof DuelService.ChallengeResult.TargetAlreadyInDuel target) {
            String targetName = resolvePlayerName(target.target());
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.target-already-in-duel", Map.of("target", targetName)));
        } else if (result instanceof DuelService.ChallengeResult.CannotDuelSelf) {
            player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "duel.cannot-duel-self"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private Optional<PlayerId> resolveOnlinePlayer(String name) {
        if (onlinePlayersSupplier != null) {
            for (Player p : onlinePlayersSupplier.get()) {
                if (p.getName().equalsIgnoreCase(name) || p.getUniqueId().toString().equalsIgnoreCase(name)) {
                    return Optional.of(PlayerId.of(p.getUniqueId()));
                }
            }
        }
        if (playerLookup != null) {
            Optional<PlayerLookup.KnownPlayer> kp = playerLookup.lookup(name);
            if (kp.isPresent() && kp.get().isOnline()) {
                return Optional.of(kp.get().id());
            }
        }
        return Optional.empty();
    }

    private String resolvePlayerName(PlayerId id) {
        if (onlinePlayersSupplier != null) {
            for (Player p : onlinePlayersSupplier.get()) {
                if (p.getUniqueId().equals(id.uuid())) {
                    return p.getName();
                }
            }
        }
        if (playerLookup != null) {
            Optional<PlayerLookup.KnownPlayer> kp = playerLookup.lookup(id.toString());
            if (kp.isPresent()) {
                return kp.get().name();
            }
        }
        return id.toString();
    }
}
