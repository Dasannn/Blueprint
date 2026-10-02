package com.dasannn.socialblueprint.domain;

import java.time.Duration;
import java.time.Instant;

/** Trigger decisions without Bukkit registry dependencies. */
public final class MindTriggers {
    private MindTriggers() {}
    public static boolean eligible(boolean enabled, boolean duel, boolean active) {
        return enabled && !duel && active;
    }
    public static boolean cleanDay(Instant anchor, Instant now, double activeMillis, double requiredMinutes) {
        return !now.isBefore(anchor.plus(Duration.ofHours(24))) && activeMillis >= requiredMinutes * 60_000;
    }
    public static boolean fishing(boolean caughtFish, boolean nonFishingActive, boolean afk) {
        return caughtFish && nonFishingActive && !afk;
    }
    public static boolean crop(String material) {
        return switch (material) {
            case "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "TORCHFLOWER_CROP", "PITCHER_CROP",
                    "TORCHFLOWER", "PITCHER_PLANT", "NETHER_WART", "COCOA", "SWEET_BERRY_BUSH" -> true;
            default -> false;
        };
    }
    public static boolean fish(String material) {
        return switch (material) {
            case "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH" -> true;
            default -> false;
        };
    }
    public static boolean feeding(int beforeAge, int afterAge, boolean foodUsed) {
        return beforeAge < 0 && afterAge > beforeAge && foodUsed;
    }
    public static boolean planting(String material, boolean farmland) { return crop(material) && farmland; }
    public static boolean harvesting(String material, int age, int maximumAge) { return crop(material) && age == maximumAge; }
    public static boolean terminalCrop(String material, boolean farmland) {
        return farmland && (material.equals("TORCHFLOWER") || material.equals("PITCHER_PLANT"));
    }

    public static final class NearDeath {
        private boolean armed = true;
        public void observe(double health, double threshold) { if (health > threshold) armed = true; }
        public boolean damage(double before, double after, double threshold) {
            observe(before, threshold);
            boolean crossed = armed && before > threshold && after > 0 && after <= threshold;
            if (crossed) armed = false;
            observe(after, threshold);
            return crossed;
        }
    }
}
