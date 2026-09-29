package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Command dispatcher for /status (and aliases /pstatus, /reputation).
 * Handles /status [player] (T-045) and /status config (T-035).
 * Resolves sender and permissions before dispatch per SB-065.
 * Captures RuntimeSnapshot once per request (T-040).
 */
public class StatusCommandExecutor implements CommandExecutor, TabCompleter {

    private final ConfigManager configManager;
    private final StatusConfigCommand configCommand;
    private final MessageRegistry messageRegistry;
    private final ProfileService profileService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            Consumer<Runnable> mainThreadRunner,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.configCommand = new StatusConfigCommand(configManager, messageRegistry);
        this.profileService = profileService;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.onlinePlayersSupplier = onlinePlayersSupplier != null ? onlinePlayersSupplier : Collections::emptyList;
    }

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            Plugin plugin
    ) {
        this(
                configManager,
                messageRegistry,
                profileService,
                runnable -> {
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, runnable);
                    } else {
                        runnable.run();
                    }
                },
                Bukkit::getOnlinePlayers
        );
    }

    public StatusCommandExecutor(ConfigManager configManager, MessageRegistry messageRegistry) {
        this(configManager, messageRegistry, null, Runnable::run, Collections::emptyList);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Capture snapshot once per request (T-040)
        RuntimeSnapshot snapshot = configManager.snapshot();

        // Subcommand: /status config ...
        if (args.length > 0 && "config".equalsIgnoreCase(args[0])) {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            return configCommand.execute(sender, subArgs, snapshot);
        }

        if (profileService == null) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
            return true;
        }

        // /status (view own profile)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
                return true;
            }

            String showPerm = snapshot.config().permissions().node("show");
            if (showPerm != null && !player.hasPermission(showPerm)) {
                player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }

            handleShowProfile(sender, player.getName(), snapshot);
            return true;
        }

        // /status <player> (view other player's profile)
        String showOthersPerm = snapshot.config().permissions().node("show-others");
        if (showOthersPerm != null && !sender.hasPermission(showOthersPerm)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return true;
        }

        String targetName = args[0];
        handleShowProfile(sender, targetName, snapshot);
        return true;
    }

    private volatile java.util.concurrent.CompletableFuture<?> lastExecution = java.util.concurrent.CompletableFuture.completedFuture(null);

    public java.util.concurrent.CompletableFuture<?> lastExecution() {
        return lastExecution;
    }

    private void handleShowProfile(CommandSender sender, String targetInput, RuntimeSnapshot snapshot) {
        this.lastExecution = executeShowProfile(sender, targetInput, snapshot);
    }

    public java.util.concurrent.CompletableFuture<Void> executeShowProfile(CommandSender sender, String targetInput, RuntimeSnapshot snapshot) {
        return profileService.resolvePlayerAsync(targetInput, snapshot.config().tiers().ladder())
                .thenAccept(optView -> mainThreadRunner.accept(() -> {
                    if (optView.isEmpty()) {
                        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                Map.of("player", targetInput)));
                    } else {
                        sendProfile(sender, optView.get(), snapshot);
                    }
                }));
    }

    public void sendProfile(CommandSender sender, PlayerSocialView view, RuntimeSnapshot snapshot) {
        Tier tier = snapshot.config().tiers().ladder().resolve(view.status());
        String prefix = snapshot.config().tiers().prefix(tier);
        String localizedTier = messageRegistry.tierName(snapshot, tier);

        String confKey = "confidence." + view.confidence().name().toLowerCase(Locale.ROOT);
        String localizedConfidence = messageRegistry.getRaw(snapshot, confKey);

        String psychKey = "psychosis." + view.psychosis().name().toLowerCase(Locale.ROOT);
        String localizedPsychosis = messageRegistry.getRaw(snapshot, psychKey);

        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-header",
                Map.of("player", view.name())));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-tier",
                Map.of("prefix", prefix, "tier", localizedTier)));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-status",
                Map.of("status", String.valueOf(view.status()))));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-confidence",
                Map.of("confidence", localizedConfidence)));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-psychosis",
                Map.of("psychosis", localizedPsychosis)));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-contributors",
                Map.of("contributors", String.valueOf(view.contributors()))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        RuntimeSnapshot snapshot = configManager.snapshot();

        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();

            String configPerm = snapshot.config().permissions().node("admin-config");
            if ((configPerm == null || sender.hasPermission(configPerm)) && "config".startsWith(current)) {
                suggestions.add("config");
            }

            for (Player player : onlinePlayersSupplier.get()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                    suggestions.add(player.getName());
                }
            }

            return suggestions;
        }

        if (args.length > 1 && "config".equalsIgnoreCase(args[0])) {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            return configCommand.tabComplete(sender, subArgs, snapshot);
        }

        return Collections.emptyList();
    }
}
