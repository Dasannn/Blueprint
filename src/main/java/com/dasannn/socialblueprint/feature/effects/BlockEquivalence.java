package com.dasannn.socialblueprint.feature.effects;

import java.util.Set;

/** Deliberately small allow-list: shapes AND client mining/interaction behaviour agree. */
public final class BlockEquivalence {
    private static final Set<String> STONE = Set.of("stone", "granite", "diorite", "andesite",
            "polished_granite", "polished_diorite", "polished_andesite");
    private static final Set<String> COLOURS = Set.of("white", "orange", "magenta", "light_blue", "yellow",
            "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");
    private BlockEquivalence() {}

    public record Position(int x, int y, int z) {}
    public record Candidate(Position position, String data, boolean standing, boolean targeted,
                            boolean held, boolean using) {}

    public static String material(String data) {
        String key = data.split("\\[", 2)[0];
        return key.startsWith("minecraft:") ? key.substring(10) : key;
    }

    public static String cubeClass(String data) {
        if (data.contains("[")) return ""; // These approved cubes have no state properties.
        String material = material(data);
        if (STONE.contains(material)) return "stone";
        for (String colour : COLOURS) {
            if (material.equals(colour + "_concrete")) return "concrete";
            if (material.equals(colour + "_terracotta")) return "terracotta";
        }
        return "";
    }

    public static boolean knownSign(String data) {
        String material = material(data);
        return WOODS.stream().anyMatch(wood -> material.equals(wood + "_sign") || material.equals(wood + "_wall_sign"));
    }

    public static boolean accepts(Candidate candidate, String fake, boolean sign) {
        if (candidate.standing() || candidate.targeted() || candidate.held() || candidate.using()) return false;
        if (sign) return knownSign(fake) && candidate.data().equals(fake);
        String group = cubeClass(candidate.data());
        return !group.isEmpty() && group.equals(cubeClass(fake));
    }
}
