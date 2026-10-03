package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.HorrorConfig;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Positions, sound keys and timings can be checked without Bukkit's registry. */
final class HorrorDecision {
    private HorrorDecision() {}
    record Step(long tick, double x, double z) {}
    record Offset(double x, double z) {}
    static Offset offset(double yaw, double distance) {
        double angle = Math.toRadians(yaw);
        return new Offset(-Math.sin(angle) * distance, Math.cos(angle) * distance);
    }
    static List<Step> footsteps(float yaw, HorrorConfig config) {
        double angle = Math.toRadians(yaw);
        return IntStream.range(0, config.steps()).mapToObj(i -> {
            double progress = (double) i / (config.steps() - 1);
            double distance = config.startDistance() + progress * (config.endDistance() - config.startDistance());
            return new Step(Math.round(progress * (config.footstepsTicks() - 10)),
                    Math.sin(angle) * distance, -Math.cos(angle) * distance);
        }).toList();
    }
    static String noiseKey(String material) {
        if (Set.of("chest", "trapped_chest", "ender_chest").contains(material))
            return material.equals("ender_chest") ? "minecraft:block.ender_chest.open" : "minecraft:block.chest.open";
        if (material.endsWith("copper_chest")) return "minecraft:block.copper_chest.open";
        String kind = material.endsWith("_trapdoor") ? "trapdoor" : material.endsWith("_door") ? "door" : "";
        if (kind.isEmpty()) return "";
        String family = material.startsWith("iron_") ? "iron_" : material.contains("copper") ? "copper_"
                : material.startsWith("cherry_") ? "cherry_wood."
                : material.startsWith("bamboo_") ? "bamboo_wood."
                : material.startsWith("crimson_") || material.startsWith("warped_") ? "nether_wood." : "wooden_";
        return "minecraft:block." + family + kind + ".open";
    }
    static boolean light(String material) {
        return Set.of("torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern", "soul_lantern").contains(material);
    }
    static boolean lookedAt(double dx, double dy, double dz, double vx, double vy, double vz, double threshold) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz) * Math.sqrt(vx * vx + vy * vy + vz * vz);
        return length == 0 || (dx * vx + dy * vy + dz * vz) / length >= threshold;
    }
    static long flickerTick(int transition, HorrorConfig config) {
        return (long) transition * config.flickerTicks() / (config.flickers() * 2);
    }
}
