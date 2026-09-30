package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration section for low-status ambient effects per SB-040, SB-043,
 * and Decision 0002.
 */
public record EffectsConfigSection(
        int threshold,
        Duration checkInterval,
        SingleEffectConfig silverfish,
        SingleEffectConfig whisper,
        SingleEffectConfig creeper,
        SingleEffectConfig fakeAnnouncement
) {
    public EffectsConfigSection {
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        Objects.requireNonNull(silverfish, "silverfish config must not be null");
        Objects.requireNonNull(whisper, "whisper config must not be null");
        Objects.requireNonNull(creeper, "creeper config must not be null");
        Objects.requireNonNull(fakeAnnouncement, "fakeAnnouncement config must not be null");
    }

    public SingleEffectConfig getEffect(AmbientEffectType type) {
        return switch (type) {
            case SILVERFISH -> silverfish;
            case WHISPER -> whisper;
            case CREEPER_SOUND -> creeper;
            case FAKE_ANNOUNCEMENT -> fakeAnnouncement;
        };
    }

    public static EffectsConfigSection defaults() {
        return new EffectsConfigSection(
                -10,
                Duration.ofSeconds(30),
                SingleEffectConfig.of(Duration.ofMinutes(10), 3, 40),
                SingleEffectConfig.of(Duration.ofMinutes(5), 5),
                SingleEffectConfig.of(Duration.ofMinutes(8), 3),
                SingleEffectConfig.of(Duration.ofMinutes(15), 2, List.of("Herobrine"))
        );
    }

    public static EffectsConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "Root ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("effects");
        if (section == null) {
            return defaults();
        }

        // 1. threshold (int, default -10)
        int threshold = section.getInt("threshold", -10);

        // 2. check-interval (Duration, strictly positive, default 30s)
        Duration checkInterval = Duration.ofSeconds(30);
        if (section.contains("check-interval")) {
            checkInterval = DurationParser.parsePositive(section.getString("check-interval"), "effects.check-interval");
        }

        // 3. silverfish
        SingleEffectConfig silverfish = loadEffect(section, "silverfish", Duration.ofMinutes(10), 3, 40, null);

        // 4. whisper
        SingleEffectConfig whisper = loadEffect(section, "whisper", Duration.ofMinutes(5), 5, 0, null);

        // 5. creeper
        SingleEffectConfig creeper = loadEffect(section, "creeper", Duration.ofMinutes(8), 3, 0, null);

        // 6. fake-announcement
        SingleEffectConfig fakeAnnouncement = loadEffect(section, "fake-announcement", Duration.ofMinutes(15), 2, 0, List.of("Herobrine"));

        return new EffectsConfigSection(threshold, checkInterval, silverfish, whisper, creeper, fakeAnnouncement);
    }

    private static SingleEffectConfig loadEffect(
            ConfigurationSection parent,
            String subKey,
            Duration defaultCooldown,
            int defaultCap,
            int defaultDurationTicks,
            List<String> defaultFakeNames
    ) {
        ConfigurationSection sub = parent.getConfigurationSection(subKey);
        String fullPrefix = "effects." + subKey;
        if (sub == null) {
            return new SingleEffectConfig(defaultCooldown, defaultCap, defaultDurationTicks, defaultFakeNames);
        }

        Duration cooldown = defaultCooldown;
        if (sub.contains("cooldown")) {
            cooldown = DurationParser.parseNonNegative(sub.getString("cooldown"), fullPrefix + ".cooldown");
        }

        int sessionCap = defaultCap;
        if (sub.contains("session-cap")) {
            sessionCap = sub.getInt("session-cap");
            if (sessionCap < 0) {
                throw new ConfigValidationException(fullPrefix + ".session-cap",
                        "Session cap must not be negative, got: " + sessionCap);
            }
        }

        int durationTicks = defaultDurationTicks;
        if (sub.contains("duration-ticks")) {
            durationTicks = sub.getInt("duration-ticks");
            if (durationTicks <= 0) {
                throw new ConfigValidationException(fullPrefix + ".duration-ticks",
                        "Duration ticks must be strictly positive, got: " + durationTicks);
            }
        }

        List<String> fakeNames = defaultFakeNames != null ? defaultFakeNames : List.of();
        if (sub.contains("fake-names")) {
            List<String> list = sub.getStringList("fake-names");
            if (list.isEmpty()) {
                throw new ConfigValidationException(fullPrefix + ".fake-names",
                        "Fake names list must not be empty");
            }
            for (int i = 0; i < list.size(); i++) {
                String name = list.get(i);
                if (name == null || name.isBlank()) {
                    throw new ConfigValidationException(fullPrefix + ".fake-names[" + i + "]",
                            "Fake player name must not be blank");
                }
            }
            fakeNames = new ArrayList<>(list);
        }

        return new SingleEffectConfig(cooldown, sessionCap, durationTicks, fakeNames);
    }
}
