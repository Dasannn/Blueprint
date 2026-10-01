package com.dasannn.socialblueprint.config;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for a single named sound slot per SB-071, SB-072, SB-092, and T-138.
 * A slot holds either a single layer or a list of layers.
 */
public record SoundSlotConfig(
        List<SoundLayerConfig> layers
) {
    public static final SoundSlotConfig SILENT = new SoundSlotConfig(Collections.emptyList());

    public SoundSlotConfig {
        if (layers == null) {
            layers = Collections.emptyList();
        } else {
            layers = List.copyOf(layers);
        }
    }

    /**
     * Backward-compatible constructor for single layer slot at delay 0.
     */
    public SoundSlotConfig(String key, float volume, float pitch, SoundCategory category) {
        this(List.of(new SoundLayerConfig(key, volume, pitch, category, 0L)));
    }

    public String key() {
        return layers.isEmpty() ? "" : layers.getFirst().key();
    }

    public float volume() {
        return layers.isEmpty() ? 1.0f : layers.getFirst().volume();
    }

    public float pitch() {
        return layers.isEmpty() ? 1.0f : layers.getFirst().pitch();
    }

    public SoundCategory category() {
        return layers.isEmpty() ? SoundCategory.MASTER : layers.getFirst().category();
    }

    public boolean isSilent() {
        if (layers.isEmpty()) {
            return true;
        }
        for (SoundLayerConfig layer : layers) {
            if (!layer.isSilent()) {
                return false;
            }
        }
        return true;
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
            for (SoundLayerConfig layer : layers) {
                if (!layer.isSilent() && layer.delay() <= 0L) {
                    player.playSound(location, layer.key(), layer.category(), layer.volume(), layer.pitch());
                }
            }
        } catch (Throwable ignored) {
            // Quiet failure per T-136
        }
    }
}
