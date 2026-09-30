package com.dasannn.socialblueprint.config;

import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Immutable configuration section for sound slots per SB-071, SB-072, T-135, and T-136.
 */
public record SoundsConfigSection(
        Map<String, SoundSlotConfig> slots
) {
    private static final Logger LOGGER = Logger.getLogger(SoundsConfigSection.class.getName());
    private static final Set<String> WARNED_CATEGORIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    public SoundsConfigSection {
        Objects.requireNonNull(slots, "slots must not be null");
        slots = Collections.unmodifiableMap(new HashMap<>(slots));
    }

    public SoundSlotConfig get(String slotName) {
        if (slotName == null || slotName.isBlank()) {
            return SoundSlotConfig.SILENT;
        }
        return slots.getOrDefault(slotName.trim().toLowerCase(Locale.ROOT), SoundSlotConfig.SILENT);
    }

    public SoundSlotConfig creeperFuse() {
        return get("creeper-fuse");
    }

    public static SoundsConfigSection defaults() {
        return new SoundsConfigSection(Collections.emptyMap());
    }

    public static SoundsConfigSection load(ConfigurationSection root) {
        return load(root, LOGGER);
    }

    public static SoundsConfigSection load(ConfigurationSection root, Logger logger) {
        Objects.requireNonNull(root, "root ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("sounds");
        if (section == null) {
            return defaults();
        }

        Logger log = logger != null ? logger : LOGGER;
        Map<String, SoundSlotConfig> slots = new HashMap<>();

        for (String slotName : section.getKeys(false)) {
            if (section.isConfigurationSection(slotName)) {
                ConfigurationSection slotSec = section.getConfigurationSection(slotName);
                if (slotSec != null) {
                    String key = slotSec.getString("key", "");

                    double rawVolume = 1.0;
                    if (slotSec.contains("volume")) {
                        Object obj = slotSec.get("volume");
                        if (obj instanceof Number num) {
                            rawVolume = num.doubleValue();
                        } else {
                            try {
                                rawVolume = Double.parseDouble(String.valueOf(obj));
                            } catch (NumberFormatException e) {
                                throw new ConfigValidationException("sounds." + slotName + ".volume",
                                        "Volume for sound slot '" + slotName + "' must be a valid number, got: " + obj);
                            }
                        }
                    }
                    float volume = (float) rawVolume;
                    if (!Float.isFinite(volume) || volume < 0.0f) {
                        throw new ConfigValidationException("sounds." + slotName + ".volume",
                                "Volume for sound slot '" + slotName + "' must be a non-negative finite number, got: " + rawVolume);
                    }

                    double rawPitch = 1.0;
                    if (slotSec.contains("pitch")) {
                        Object obj = slotSec.get("pitch");
                        if (obj instanceof Number num) {
                            rawPitch = num.doubleValue();
                        } else {
                            try {
                                rawPitch = Double.parseDouble(String.valueOf(obj));
                            } catch (NumberFormatException e) {
                                throw new ConfigValidationException("sounds." + slotName + ".pitch",
                                        "Pitch for sound slot '" + slotName + "' must be a valid number, got: " + obj);
                            }
                        }
                    }
                    float pitch = (float) rawPitch;
                    if (!Float.isFinite(pitch) || pitch < 0.0f || pitch > 2.0f) {
                        throw new ConfigValidationException("sounds." + slotName + ".pitch",
                                "Pitch for sound slot '" + slotName + "' must be a finite number between 0.0 and 2.0, got: " + rawPitch);
                    }

                    String rawCat = slotSec.getString("category", "MASTER");
                    SoundCategory category = parseCategory(rawCat, slotName, log);
                    slots.put(slotName.toLowerCase(Locale.ROOT), new SoundSlotConfig(key, volume, pitch, category));
                }
            }
        }

        return new SoundsConfigSection(slots);
    }

    public static SoundCategory parseCategory(String raw, String slotName, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return SoundCategory.MASTER;
        }
        try {
            return SoundCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (WARNED_CATEGORIES.add(slotName.toLowerCase(Locale.ROOT))) {
                if (logger != null) {
                    logger.warning("[SocialBlueprint] Unrecognised sound category '" + raw
                            + "' for sound slot '" + slotName + "'; falling back to MASTER");
                }
            }
            return SoundCategory.MASTER;
        }
    }

    public void logKeyWarning(String slotName, String key, String error) {
        if (WARNED_KEYS.add(slotName.toLowerCase(Locale.ROOT))) {
            LOGGER.warning("[SocialBlueprint] Unrecognised or failed sound key '" + key
                    + "' for sound slot '" + slotName + "': " + error);
        }
    }
}
