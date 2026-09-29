package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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

        // Console sender is permitted for all console-capable actions
        if (!(sender instanceof Player player)) {
            return true;
        }

        // 1. Check configured permission node from runtime snapshot
        String configuredNode = snapshot.config().permissions().node(actionKey);
        if (configuredNode != null && player.hasPermission(configuredNode)) {
            return true;
        }

        // Parent permissions declared in plugin.yml
        if (player.hasPermission("socialblueprint.*")) {
            return true;
        }
        if (actionKey.startsWith("admin") && player.hasPermission("socialblueprint.admin")) {
            return true;
        }

        // 2. Backward compatibility: consult legacy pstatus.* nodes
        List<String> legacyNodes = legacyNodesFor(actionKey);
        for (String legacy : legacyNodes) {
            if (player.hasPermission(legacy)) {
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
            default -> List.of();
        };
    }
}
