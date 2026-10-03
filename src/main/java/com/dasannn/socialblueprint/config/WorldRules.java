package com.dasannn.socialblueprint.config;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Exact Bukkit world names; no Bukkit objects cross the snapshot boundary. */
public record WorldRules(List<String> disabledWorlds) {
    public WorldRules { disabledWorlds = List.copyOf(disabledWorlds); }
    public static WorldRules defaults() { return new WorldRules(List.of("minigames")); }
    public boolean isDisabled(String world) { return world != null && disabledWorlds.contains(world); }
    public boolean allowsWorld(String world) { return !isDisabled(world); }
    public static WorldRules load(ConfigurationSection root) {
        if (!root.contains("disabled-worlds")) return defaults();
        List<?> values = root.getList("disabled-worlds");
        if (values == null || values.stream().anyMatch(value -> !(value instanceof String)))
            throw new ConfigValidationException("disabled-worlds", "Must be a list of strings");
        return new WorldRules(values.stream().map(String.class::cast).toList());
    }
}
