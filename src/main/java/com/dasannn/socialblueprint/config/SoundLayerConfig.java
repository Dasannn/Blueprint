package com.dasannn.socialblueprint.config;

import org.bukkit.SoundCategory;

/**
 * Immutable configuration for a single layer within a sound slot per SB-092 and T-138.
 */
public record SoundLayerConfig(
        String key,
        float volume,
        float pitch,
        SoundCategory category,
        long delay
) {
    public static final SoundLayerConfig SILENT = new SoundLayerConfig("", 1.0f, 1.0f, SoundCategory.MASTER, 0L);

    public SoundLayerConfig {
        if (key == null) {
            key = "";
        }
        if (category == null) {
            category = SoundCategory.MASTER;
        }
        if (!Float.isFinite(volume) || volume < 0.0f) {
            throw new IllegalArgumentException("Volume must be a non-negative finite number, got: " + volume);
        }
        if (!Float.isFinite(pitch) || pitch < 0.0f || pitch > 2.0f) {
            throw new IllegalArgumentException("Pitch must be a finite number between 0.0 and 2.0, got: " + pitch);
        }
        if (delay < 0L) {
            throw new IllegalArgumentException("Delay must be a non-negative number of ticks, got: " + delay);
        }
    }

    public SoundLayerConfig(String key, float volume, float pitch, SoundCategory category) {
        this(key, volume, pitch, category, 0L);
    }

    public boolean isSilent() {
        return key.isBlank();
    }
}
