package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SB-137. Bukkit identity and replies stay on the calling/main thread. */
public final class StatusMindCommand {
    private final ProfileService profiles;
    private final MessageRegistry messages;
    private final Consumer<Runnable> mainThread;
    private final Clock clock;
    private final Map<PlayerId, Instant> confirmations = new HashMap<>();
    public StatusMindCommand(ProfileService profiles, MessageRegistry messages, Consumer<Runnable> mainThread, Clock clock) {
        this.profiles = profiles; this.messages = messages; this.mainThread = mainThread; this.clock = clock;
    }
    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!PermissionChecker.hasPermission(sender, "admin-mind", snapshot)) {
            reply(sender, snapshot, "commands.no-permission", Map.of());
            return CompletableFuture.completedFuture(null);
        }
        PlayerId actor = sender instanceof Player player ? PlayerId.of(player.getUniqueId()) : PlayerId.CONSOLE;
        Instant now = clock.instant();
        CompletableFuture<Void> operation;
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) {
            var value = ArgumentParser.parseMindValue(args[2]);
            if (value.isEmpty()) {
                reply(sender, snapshot, "mind-admin.invalid-value", Map.of("value", args[2]));
                return CompletableFuture.completedFuture(null);
            }
            String adminName = sender.getName();
            operation = profiles.resolveTargetIdentityAsync(args[1]).thenCompose(target -> {
                if (target.isEmpty()) {
                    reply(sender, snapshot, "status.not-found", Map.of("player", args[1]));
                    return CompletableFuture.completedFuture(null);
                }
                return profiles.mind().setAsync(target.get().id(), value.getAsDouble(), actor, adminName, now)
                        .thenRun(() -> reply(sender, snapshot, "mind-admin.set",
                                Map.of("player", target.get().name(), "value", Double.toString(value.getAsDouble()))));
            });
        } else if (args.length == 3 && "reduce".equalsIgnoreCase(args[0])) {
            double percent;
            try { percent = Double.parseDouble(args[2]); }
            catch (NumberFormatException error) { percent = Double.NaN; }
            if (!Double.isFinite(percent) || percent <= 0 || percent > 100) {
                reply(sender, snapshot, "mind-admin.invalid-percent", Map.of("percent", args[2]));
                return CompletableFuture.completedFuture(null);
            }
            double reduction = percent;
            String adminName = sender.getName();
            operation = profiles.resolveTargetIdentityAsync(args[1]).thenCompose(target -> {
                if (target.isEmpty()) {
                    reply(sender, snapshot, "status.not-found", Map.of("player", args[1]));
                    return CompletableFuture.completedFuture(null);
                }
                return profiles.mind().reduceAsync(target.get().id(), reduction, actor, adminName, now).thenAccept(result ->
                        reply(sender, snapshot, result.enabled() ? "mind-admin.reduced" : "mind-admin.reduce-unchanged",
                                Map.of("player", target.get().name(), "percent", Double.toString(reduction),
                                       "value", String.format(Locale.ROOT, "%.1f", Math.max(0, -result.after())))));
            });
        } else if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            String input = args[1];
            operation = profiles.resolveTargetIdentityAsync(input).thenCompose(target -> {
                if (target.isEmpty()) {
                    reply(sender, snapshot, "status.not-found", Map.of("player", input));
                    return CompletableFuture.completedFuture(null);
                }
                return profiles.mind().resetAsync(target.get().id(), actor, now).thenAccept(count ->
                        reply(sender, snapshot, "mind-admin.reset", Map.of("player", target.get().name())));
            });
        } else if ((args.length == 1 || args.length == 2 && "confirm".equalsIgnoreCase(args[1]))
                && "reset-all".equalsIgnoreCase(args[0])) {
            confirmations.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
            if (args.length == 1) {
                confirmations.put(actor, now.plusSeconds(30));
                reply(sender, snapshot, "mind-admin.confirm", Map.of());
                return CompletableFuture.completedFuture(null);
            }
            Instant expires = confirmations.remove(actor);
            if (expires == null || !now.isBefore(expires)) {
                reply(sender, snapshot, "mind-admin.confirm-expired", Map.of());
                return CompletableFuture.completedFuture(null);
            }
            operation = profiles.mind().resetAllAsync(actor, now).thenAccept(count ->
                    reply(sender, snapshot, "mind-admin.reset-all", Map.of("count", Integer.toString(count))));
        } else {
            reply(sender, snapshot, "mind-admin.usage", Map.of());
            return CompletableFuture.completedFuture(null);
        }
        return operation.exceptionally(error -> {
            Logger.getLogger(StatusMindCommand.class.getName()).log(Level.WARNING, "Mental-state reset failed", error);
            reply(sender, snapshot, "mind-admin.failed", Map.of());
            return null;
        });
    }
    private void reply(CommandSender sender, RuntimeSnapshot snapshot, String key, Map<String, String> values) {
        mainThread.accept(() -> sender.sendMessage(messages.renderWithPrefix(snapshot, key, values)));
    }
    public List<String> tabComplete(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!PermissionChecker.hasPermission(sender, "admin-mind", snapshot)) return List.of();
        if (args.length == 1) return List.of("reset", "reset-all", "set", "reduce").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && "reset-all".equalsIgnoreCase(args[0]) && "confirm".startsWith(args[1].toLowerCase(Locale.ROOT)))
            return List.of("confirm");
        if (args.length == 3 && "reduce".equalsIgnoreCase(args[0])) return List.of("40", "50", "100").stream()
                .filter(value -> value.startsWith(args[2])).toList();
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) return List.of("-100", "0", "100").stream()
                .filter(value -> value.startsWith(args[2])).toList();
        return List.of();
    }
}
