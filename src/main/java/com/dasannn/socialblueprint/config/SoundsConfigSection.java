package com.dasannn.socialblueprint.config;

import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
                    float volume = parseVolume(slotSec.contains("volume") ? slotSec.get("volume") : null,
                            "sounds." + slotName + ".volume", slotName);
                    float pitch = parsePitch(slotSec.contains("pitch") ? slotSec.get("pitch") : null,
                            "sounds." + slotName + ".pitch", slotName);
                    String rawCat = slotSec.getString("category", "MASTER");
                    SoundCategory category = parseCategory(rawCat, slotName, log);
                    long delay = parseDelay(slotSec.contains("delay") ? slotSec.get("delay") : null,
                            "sounds." + slotName + ".delay");

                    slots.put(slotName.toLowerCase(Locale.ROOT),
                            new SoundSlotConfig(List.of(new SoundLayerConfig(key, volume, pitch, category, delay))));
                }
            } else if (section.isList(slotName)) {
                List<?> list = section.getList(slotName);
                List<SoundLayerConfig> layers = new ArrayList<>();
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if (item instanceof Map<?, ?> map) {
                            String key = map.containsKey("key") ? String.valueOf(map.get("key")) : "";
                            float volume = parseVolume(map.get("volume"),
                                    "sounds." + slotName + "[" + i + "].volume", slotName);
                            float pitch = parsePitch(map.get("pitch"),
                                    "sounds." + slotName + "[" + i + "].pitch", slotName);
                            String rawCat = map.containsKey("category") ? String.valueOf(map.get("category")) : "MASTER";
                            SoundCategory category = parseCategory(rawCat, slotName, log);
                            long delay = parseDelay(map.get("delay"),
                                    "sounds." + slotName + "[" + i + "].delay");

                            layers.add(new SoundLayerConfig(key, volume, pitch, category, delay));
                        }
                    }
                }
                slots.put(slotName.toLowerCase(Locale.ROOT), new SoundSlotConfig(layers));
            }
        }

        return new SoundsConfigSection(slots);
    }

    private static float parseVolume(Object obj, String path, String slotName) {
        if (obj == null) {
            return 1.0f;
        }
        double rawVolume;
        if (obj instanceof Number num) {
            rawVolume = num.doubleValue();
        } else {
            try {
                rawVolume = Double.parseDouble(String.valueOf(obj));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(path,
                        "Volume for sound slot '" + slotName + "' must be a valid number, got: " + obj);
            }
        }
        float volume = (float) rawVolume;
        if (!Float.isFinite(volume) || volume < 0.0f) {
            throw new ConfigValidationException(path,
                    "Volume for sound slot '" + slotName + "' must be a non-negative finite number, got: " + rawVolume);
        }
        return volume;
    }

    private static float parsePitch(Object obj, String path, String slotName) {
        if (obj == null) {
            return 1.0f;
        }
        double rawPitch;
        if (obj instanceof Number num) {
            rawPitch = num.doubleValue();
        } else {
            try {
                rawPitch = Double.parseDouble(String.valueOf(obj));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(path,
                        "Pitch for sound slot '" + slotName + "' must be a valid number, got: " + obj);
            }
        }
        float pitch = (float) rawPitch;
        if (!Float.isFinite(pitch) || pitch < 0.0f || pitch > 2.0f) {
            throw new ConfigValidationException(path,
                    "Pitch for sound slot '" + slotName + "' must be a finite number between 0.0 and 2.0, got: " + rawPitch);
        }
        return pitch;
    }

    private static long parseDelay(Object obj, String path) {
        if (obj == null) {
            return 0L;
        }
        return DurationParser.parseTicks(obj, path);
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
