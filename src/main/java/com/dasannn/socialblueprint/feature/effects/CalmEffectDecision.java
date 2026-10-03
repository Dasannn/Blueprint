package com.dasannn.socialblueprint.feature.effects;

import java.util.Set;

/** Plain decisions for the private serenity additions; no registry initialization. */
public final class CalmEffectDecision {
    private CalmEffectDecision() {}
    // Conservative client lifetime bound for both vanilla particle types.
    public static final int PARTICLE_TAIL = 400;
    public static final Set<String> PRIVATE_EFFECTS = Set.of("dawn", "flowers", "clear-sky",
            "ambient-particles", "music", "warm-phrases", "glowing-animals");
    public static final Set<String> PASSIVE_ANIMALS = Set.of("COW", "SHEEP", "PIG", "CHICKEN", "RABBIT", "TURTLE",
            "FOX", "CAT", "OCELOT", "HORSE", "DONKEY", "MULE", "LLAMA", "TRADER_LLAMA", "MOOSHROOM",
            "ARMADILLO", "SNIFFER", "AXOLOTL");
    public static boolean privateEffect(String effect) { return PRIVATE_EFFECTS.contains(effect); }
    public static boolean needsClear(String override, boolean worldStorm) {
        return override == null ? worldStorm : override.equals("DOWNFALL");
    }
    public static String particle(long time) {
        long dayTime = Math.floorMod(time, 24000);
        return dayTime >= 13000 && dayTime < 23000 ? "firefly" : "cherry_leaves";
    }
    public static boolean flowerSite(boolean air, String ground, double distanceSquared, double range,
                                     double eyeDistanceSquared, double reach) {
        return air && Set.of("grass_block", "dirt", "coarse_dirt", "rooted_dirt").contains(ground)
                && Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= range * range
                && Double.isFinite(reach) && reach >= 0 && Double.isFinite(eyeDistanceSquared) && eyeDistanceSquared > reach * reach;
    }
    public static byte glowingFlags(byte flags) { return (byte) (flags | 0x40); }
    public static int phraseIndex(int previous, int size) { return Math.floorMod(previous, size); }
}
