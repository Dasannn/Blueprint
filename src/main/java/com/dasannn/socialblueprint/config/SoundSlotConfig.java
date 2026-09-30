package com.dasannn.socialblueprint.config;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Immutable configuration for a single named sound slot per SB-071, SB-072, and T-135.
 * Key is the Minecraft sound key (e.g. "entity.creeper.primed"). Empty key = silence.
 */
public record SoundSlotConfig(
        String key,
        float volume,
        float pitch,
        SoundCategory category
) {
    public static final SoundSlotConfig SILENT = new SoundSlotConfig("", 1.0f, 1.0f, SoundCategory.MASTER);

    public SoundSlotConfig {
        if (key == null) {
            key = "";
        }
        if (category == null) {
            category = SoundCategory.MASTER;
        }
    }

    public boolean isSilent() {
        return key.isBlank();
    }

    /**
     * Plays this sound privately to the player (SB-041, T-137).
     * Never throws and never blocks (T-136).
     */
    public void play(Player player) {
        if (player == null || isSilent()) {
            return;
        }
        try {
            play(player, player.getLocation());
        } catch (Throwable ignored) {
            // Quiet failure per T-136
        }
    }

    public void play(Player player, Location location) {
        if (player == null || location == null || isSilent()) {
            return;
        }
        try {
            player.playSound(location, key, category, volume, pitch);
        } catch (Throwable ignored) {
            // Quiet failure per T-136
        }
    }
}
