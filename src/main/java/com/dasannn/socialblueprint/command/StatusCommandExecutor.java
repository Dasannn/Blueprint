package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Command dispatcher for /status (and aliases /pstatus, /reputation).
 * In Phase P2, handles /status config per T-035.
 */
public class StatusCommandExecutor implements CommandExecutor, TabCompleter {

    private final StatusConfigCommand configCommand;
    private final MessageRegistry messageRegistry;

    public StatusCommandExecutor(ConfigManager configManager, MessageRegistry messageRegistry) {
        Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.configCommand = new StatusConfigCommand(configManager, messageRegistry);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && "config".equalsIgnoreCase(args[0])) {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            return configCommand.execute(sender, subArgs);
        }

        // For P2, only /status config is implemented. Prompt usage.
        sender.sendMessage(messageRegistry.renderWithPrefix("commands.config.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if ("config".startsWith(args[0].toLowerCase())) {
                return List.of("config");
            }
            return Collections.emptyList();
        }

        if (args.length > 1 && "config".equalsIgnoreCase(args[0])) {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            return configCommand.tabComplete(sender, subArgs);
        }

        return Collections.emptyList();
    }
}
