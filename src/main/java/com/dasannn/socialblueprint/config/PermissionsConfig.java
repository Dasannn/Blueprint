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
        String node = nodes.get(action);
        if (node == null && "effects".equals(action)) {
            return "socialblueprint.effects";
        }
        return node;
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
