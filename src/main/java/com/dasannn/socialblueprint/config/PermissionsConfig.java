package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration section for permission node mappings per SB-061 and T-054.
 * Action-to-node mapping is resolved at check time.
 */
public record PermissionsConfig(
        Map<String, String> nodes
) {
    public static final Set<String> REQUIRED_ACTIONS = Set.of(
            "show",
            "show-others",
            "give-reputation",
            "take-reputation",
            "view-reputation",
            "admin-adjust",
            "admin-config"
    );

    public PermissionsConfig {
        Objects.requireNonNull(nodes, "Nodes map must not be null");
        nodes = Collections.unmodifiableMap(new HashMap<>(nodes));
    }

    public String node(String action) {
        String val = nodes.get(action);
        if (val != null) {
            return val;
        }
        return switch (action) {
            case "version" -> "socialblueprint.version";
            case "admin-features" -> "socialblueprint.admin.features";
            case "admin-mind" -> "socialblueprint.admin.mind";
            case "admin-update" -> "socialblueprint.admin.update";
            case "admin-revoke" -> "socialblueprint.admin.revoke";
            default -> null;
        };
    }

    public static PermissionsConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("permissions");
        if (section == null) {
            throw new ConfigValidationException("permissions", "Missing required configuration section 'permissions'");
        }

        Map<String, String> nodes = new HashMap<>();
        for (String action : REQUIRED_ACTIONS) {
            String fullKey = "permissions." + action;
            if (!section.contains(action)) {
                throw new ConfigValidationException(fullKey, "Missing required permission mapping: " + fullKey);
            }
            String node = section.getString(action);
            if (node == null || node.isBlank()) {
                throw new ConfigValidationException(fullKey, "Permission node must not be blank for: " + fullKey);
            }
            nodes.put(action, node);
        }

        if (section.contains("admin-revoke") && (section.getString("admin-revoke") == null
                || section.getString("admin-revoke").isBlank()))
            throw new ConfigValidationException("permissions.admin-revoke", "Permission node must not be blank");
        if (section.contains("admin-mind") && (!section.isString("admin-mind") || section.getString("admin-mind").isBlank()))
            throw new ConfigValidationException("permissions.admin-mind", "Permission node must be a nonblank string");

        if (section.contains("admin-features") && (!section.isString("admin-features") || section.getString("admin-features").isBlank()))
            throw new ConfigValidationException("permissions.admin-features", "Permission node must be a nonblank string");

        // Include any additional custom nodes configured
        for (String key : section.getKeys(false)) {
            if (!nodes.containsKey(key)) {
                String node = section.getString(key);
                if (node != null && !node.isBlank()) {
                    nodes.put(key, node);
                }
            }
        }

        return new PermissionsConfig(nodes);
    }
}
