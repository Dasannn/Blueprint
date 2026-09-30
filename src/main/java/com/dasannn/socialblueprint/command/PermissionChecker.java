package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Objects;

/**
 * Checks permissions using Permissible#hasPermission per T-053, T-054, and ARCHITECTURE.md §7.
 * Action-to-node mappings are read from the RuntimeSnapshot at check time (SB-061).
 * To guarantee that existing LuckPerms/server grants keep working, legacy pstatus.* nodes
 * are also consulted if the new node is not granted.
 */
public final class PermissionChecker {

    private PermissionChecker() {
    }

    public static boolean hasPermission(CommandSender sender, String actionKey, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(actionKey, "actionKey must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Permissible#hasPermission is declared on CommandSender, so the same path
        // serves players, the console and command blocks. The console is granted
        // everything by the server itself; it needs no special case here, and a
        // special case would also wave through command blocks and remote console.

        // 1. Check configured permission node from runtime snapshot
        String configuredNode = snapshot.config().permissions().node(actionKey);
        if (configuredNode != null && sender.hasPermission(configuredNode)) {
            return true;
        }

        // Parent permissions declared in plugin.yml
        if (sender.hasPermission("socialblueprint.*")) {
            return true;
        }
        if (actionKey.startsWith("admin") && sender.hasPermission("socialblueprint.admin")) {
            return true;
        }

        // 2. Backward compatibility: consult legacy pstatus.* nodes
        List<String> legacyNodes = legacyNodesFor(actionKey);
        for (String legacy : legacyNodes) {
            if (sender.hasPermission(legacy)) {
                return true;
            }
        }

        return false;
    }

    public static List<String> legacyNodesFor(String actionKey) {
        return switch (actionKey) {
            case "show" -> List.of("pstatus.show");
            case "show-others" -> List.of("pstatus.showOtherPlayers");
            case "give-reputation" -> List.of("pstatus.giveReputation");
            case "take-reputation" -> List.of("pstatus.giveReputation", "pstatus.addRemoveRep");
            case "view-reputation" -> List.of("pstatus.viewReputation");
            case "admin-adjust" -> List.of("pstatus.addRemoveRep", "pstatus.setReputation", "pstatus.admin");
            case "admin-config" -> List.of("pstatus.admin");
            case "admin-update" -> List.of("pstatus.admin");
            case "version" -> List.of("pstatus.admin", "pstatus.show");
            default -> List.of();
        };
    }
}
