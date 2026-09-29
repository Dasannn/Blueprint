package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import net.kyori.adventure.text.Component;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Command dispatcher for /status (and aliases /pstatus, /reputation) per T-050, T-051, and ARCHITECTURE.md §8.
 * - Resolves sender before dispatch: console never reaches player-only code (SB-065).
 * - Centralised argument parsing: no raw Integer.parseInt (T-051).
 * - Offline targets resolved by UUID from player_profile first, falling back to Bukkit offline lookup (T-052).
 * - Captures RuntimeSnapshot once per request and threads it through (T-040).
 */
public class StatusCommandExecutor implements CommandExecutor, TabCompleter {

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final ProfileService profileService;
    private final HonorService honorService;
    private final StatusConfigCommand configCommand;
    private final StatusAdminCommand adminCommand;
    private final StatusTrustCommand trustCommand;
    private final StatusDistrustCommand distrustCommand;
    private final StatusConfirmCommand confirmCommand;
    private final Consumer<Runnable> mainThreadRunner;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;

    private volatile CompletableFuture<?> lastExecution = CompletableFuture.completedFuture(null);

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            HonorService honorService,
            Consumer<Runnable> mainThreadRunner,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.profileService = profileService;
        this.honorService = honorService;
        this.configCommand = new StatusConfigCommand(configManager, messageRegistry);
        this.adminCommand = honorService != null ? new StatusAdminCommand(honorService, messageRegistry) : null;
        this.trustCommand = honorService != null ? new StatusTrustCommand(honorService, messageRegistry) : null;
        this.distrustCommand = honorService != null ? new StatusDistrustCommand(honorService, messageRegistry) : null;
        this.confirmCommand = honorService != null ? new StatusConfirmCommand(honorService, messageRegistry) : null;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.onlinePlayersSupplier = onlinePlayersSupplier != null ? onlinePlayersSupplier : Collections::emptyList;
    }

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            Consumer<Runnable> mainThreadRunner,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier
    ) {
        this(configManager, messageRegistry, profileService, null, mainThreadRunner, onlinePlayersSupplier);
    }

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            HonorService honorService,
            Plugin plugin
    ) {
        this(
                configManager,
                messageRegistry,
                profileService,
                honorService,
                runnable -> {
                    if (plugin != null && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, runnable);
                    } else {
                        runnable.run();
                    }
                },
                Bukkit::getOnlinePlayers
        );
    }

    public StatusCommandExecutor(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ProfileService profileService,
            Plugin plugin
    ) {
        this(configManager, messageRegistry, profileService, null, plugin);
    }

    public StatusCommandExecutor(ConfigManager configManager, MessageRegistry messageRegistry) {
        this(configManager, messageRegistry, null, null, Runnable::run, Collections::emptyList);
    }

    public CompletableFuture<?> lastExecution() {
        return lastExecution;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Capture snapshot once per request (T-040)
        RuntimeSnapshot snapshot = configManager.snapshot();

        // 1. /status with no args: view own profile (Player only)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
                return true;
            }

            if (!PermissionChecker.hasPermission(player, "show", snapshot)) {
                player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }

            handleShowProfile(sender, player.getName(), snapshot);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        // 2. Subcommand: /status config ...
        if ("config".equals(sub)) {
            return configCommand.execute(sender, subArgs, snapshot);
        }

        // 3. Subcommand: /status admin ...
        if ("admin".equals(sub)) {
            if (adminCommand == null) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }
            this.lastExecution = adminCommand.execute(sender, subArgs, snapshot);
            return true;
        }

        // 4. Subcommand: /status trust <player> [reason] or /status give <player> [reason]
        if ("trust".equals(sub) || "give".equals(sub)) {
            if (trustCommand == null) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }
            this.lastExecution = trustCommand.execute(sender, subArgs, snapshot);
            return true;
        }

        // 5. Subcommand: /status distrust <player> <reason> or /status take <player> <reason>
        if ("distrust".equals(sub) || "take".equals(sub) || "remove".equals(sub)) {
            if (distrustCommand == null) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }
            this.lastExecution = distrustCommand.execute(sender, subArgs, snapshot);
            return true;
        }

        // 6. Subcommand: /status confirm
        if ("confirm".equals(sub)) {
            if (confirmCommand == null) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }
            this.lastExecution = confirmCommand.execute(sender, subArgs, snapshot);
            return true;
        }

        // 7. Legacy syntax: /status info <player> or /pstatus info <player>
        if ("info".equals(sub) && subArgs.length >= 1) {
            if (!PermissionChecker.hasPermission(sender, "show-others", snapshot)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
                return true;
            }
            handleShowProfile(sender, subArgs[0], snapshot);
            return true;
        }

        // 8. Legacy vote syntax: /reputation <player> + [reason] or /reputation <player> - <reason>
        if (args.length >= 2 && ("+".equals(args[1]) || "-".equals(args[1]))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.player-only"));
                return true;
            }
            String target = args[0];
            String sign = args[1];
            String[] reasonArgs = Arrays.copyOfRange(args, 2, args.length);
            if ("+".equals(sign)) {
                String[] forwardArgs = new String[1 + reasonArgs.length];
                forwardArgs[0] = target;
                System.arraycopy(reasonArgs, 0, forwardArgs, 1, reasonArgs.length);
                if (trustCommand != null) {
                    this.lastExecution = trustCommand.execute(player, forwardArgs, snapshot);
                }
                return true;
            } else {
                if (reasonArgs.length < 1) {
                    player.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-required"));
                    return true;
                }
                String[] forwardArgs = new String[1 + reasonArgs.length];
                forwardArgs[0] = target;
                System.arraycopy(reasonArgs, 0, forwardArgs, 1, reasonArgs.length);
                if (distrustCommand != null) {
                    this.lastExecution = distrustCommand.execute(player, forwardArgs, snapshot);
                }
                return true;
            }
        }

        // Unknown multi-arg subcommand
        if (args.length > 1) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.unknown-subcommand",
                    Map.of("subcommand", sub)));
            return true;
        }

        // 9. /status <player> (view other player's profile)
        if (profileService == null) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.config.usage"));
            return true;
        }

        if (!PermissionChecker.hasPermission(sender, "show-others", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return true;
        }

        String targetName = args[0];
        handleShowProfile(sender, targetName, snapshot);
        return true;
    }

    private void handleShowProfile(CommandSender sender, String targetInput, RuntimeSnapshot snapshot) {
        this.lastExecution = executeShowProfile(sender, targetInput, snapshot);
    }

    public CompletableFuture<Void> executeShowProfile(CommandSender sender, String targetInput, RuntimeSnapshot snapshot) {
        return profileService.resolvePlayerAsync(targetInput, snapshot)
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

        Component prefixComp = (prefix != null && !prefix.isEmpty())
                ? ColorParser.parse(prefix)
                : Component.empty();

        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-header",
                Map.of("player", view.name())));
        sender.sendMessage(messageRegistry.render(snapshot, "status.profile-tier",
                Map.of("tier", localizedTier),
                Map.of("prefix", prefixComp)));
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

            if (PermissionChecker.hasPermission(sender, "admin-config", snapshot) && "config".startsWith(current)) {
                suggestions.add("config");
            }

            if (PermissionChecker.hasPermission(sender, "admin-adjust", snapshot) && "admin".startsWith(current)) {
                suggestions.add("admin");
            }

            if (sender instanceof Player) {
                if (PermissionChecker.hasPermission(sender, "give-reputation", snapshot)) {
                    if ("trust".startsWith(current)) suggestions.add("trust");
                    if ("give".startsWith(current)) suggestions.add("give");
                }
                if (PermissionChecker.hasPermission(sender, "take-reputation", snapshot)) {
                    if ("distrust".startsWith(current)) suggestions.add("distrust");
                    if ("take".startsWith(current)) suggestions.add("take");
                }
                if ("confirm".startsWith(current)) {
                    suggestions.add("confirm");
                }
            }

            for (Player player : onlinePlayersSupplier.get()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                    suggestions.add(player.getName());
                }
            }

            return suggestions;
        }

        if (args.length > 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

            if ("config".equals(sub)) {
                return configCommand.tabComplete(sender, subArgs, snapshot);
            }

            if ("admin".equals(sub) && adminCommand != null) {
                if (subArgs.length == 1) {
                    return adminCommand.tabComplete(sender, subArgs, snapshot);
                }
                if (subArgs.length == 2) {
                    String current = subArgs[1].toLowerCase(Locale.ROOT);
                    List<String> playerMatches = new ArrayList<>();
                    for (Player player : onlinePlayersSupplier.get()) {
                        if (player.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                            playerMatches.add(player.getName());
                        }
                    }
                    return playerMatches;
                }
            }

            if (("trust".equals(sub) || "give".equals(sub) || "distrust".equals(sub) || "take".equals(sub) || "info".equals(sub))
                    && subArgs.length == 1) {
                String current = subArgs[0].toLowerCase(Locale.ROOT);
                List<String> playerMatches = new ArrayList<>();
                for (Player player : onlinePlayersSupplier.get()) {
                    if (!player.getName().equalsIgnoreCase(sender.getName()) && player.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                        playerMatches.add(player.getName());
                    }
                }
                return playerMatches;
            }
        }

        return Collections.emptyList();
    }
}
