package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for private Psychosis episodes (SB-043, SB-097).
 */
public record EffectsConfigSection(
        Duration checkInterval,
        SingleEffectConfig silverfish,
        SingleEffectConfig whisper,
        SingleEffectConfig creeper,
        SingleEffectConfig fakeAnnouncement,
        Duration mediumQuietInterval,
        Duration highQuietInterval,
        Duration extremeQuietInterval,
        int maxEpisodeTicks,
        PresentationConfig presentation,
        SerenityEffectsConfig serenity
) {
    public EffectsConfigSection(Duration checkInterval, SingleEffectConfig silverfish, SingleEffectConfig whisper,
                                SingleEffectConfig creeper, SingleEffectConfig fakeAnnouncement, Duration medium,
                                Duration high, Duration extreme, int maxEpisodeTicks, PresentationConfig presentation) {
        this(checkInterval, silverfish, whisper, creeper, fakeAnnouncement, medium, high, extreme,
                maxEpisodeTicks, presentation, SerenityEffectsConfig.defaults());
    }
    public EffectsConfigSection {
        Objects.requireNonNull(presentation, "presentation must not be null");
        Objects.requireNonNull(serenity, "serenity must not be null");
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        if (checkInterval.isNegative() || checkInterval.isZero()
                || checkInterval.compareTo(Duration.ofMillis((Long.MAX_VALUE - 20_000L) / 3L)) > 0) {
            throw new ConfigValidationException("effects.check-interval", "Check interval must be positive and fit the episode cadence");
        }
        Objects.requireNonNull(silverfish, "silverfish config must not be null");
        Objects.requireNonNull(whisper, "whisper config must not be null");
        Objects.requireNonNull(creeper, "creeper config must not be null");
        Objects.requireNonNull(fakeAnnouncement, "fakeAnnouncement config must not be null");
        mediumQuietInterval = floor(mediumQuietInterval, 3);
        highQuietInterval = floor(highQuietInterval, 2);
        extremeQuietInterval = floor(extremeQuietInterval, 1);
        if (mediumQuietInterval.compareTo(highQuietInterval) <= 0
                || highQuietInterval.compareTo(extremeQuietInterval) <= 0) {
            throw new ConfigValidationException("effects.quiet-interval", "Intervals must decrease from medium to high to extreme");
        }
        if (maxEpisodeTicks < 1 || maxEpisodeTicks > 200) {
            throw new ConfigValidationException("effects.max-episode-ticks", "Episode must last 1 to 200 ticks");
        }
    }

    public EffectsConfigSection(Duration checkInterval, SingleEffectConfig silverfish,
                                SingleEffectConfig whisper, SingleEffectConfig creeper, SingleEffectConfig fakeAnnouncement,
                                Duration medium, Duration high, Duration extreme, int maxEpisodeTicks) {
        this(checkInterval, silverfish, whisper, creeper, fakeAnnouncement, medium, high, extreme,
                maxEpisodeTicks, PresentationConfig.defaults());
    }

    public EffectsConfigSection(Duration checkInterval, SingleEffectConfig silverfish,
                                SingleEffectConfig whisper, SingleEffectConfig creeper, SingleEffectConfig fakeAnnouncement) {
        this(checkInterval, silverfish, whisper, creeper, fakeAnnouncement,
                Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofSeconds(20), 200);
    }

    private static Duration floor(Duration value, int seconds) {
        Objects.requireNonNull(value, "quiet interval must not be null");
        if (value.isNegative()) {
            throw new ConfigValidationException("effects.quiet-interval", "Quiet interval must not be negative");
        }
        if (value.compareTo(Duration.ofMillis(Long.MAX_VALUE - 20_000L)) > 0) {
            throw new ConfigValidationException("effects.quiet-interval", "Quiet interval is too large");
        }
        return value.compareTo(Duration.ofSeconds(seconds)) < 0 ? Duration.ofSeconds(seconds) : value;
    }

    public Duration quietInterval(PsychosisLevel level) {
        Duration configured = switch (level) {
            case LOW, NEUTRAL, SERENITY -> throw new IllegalArgumentException("This direction has no madness episodes");
            case MEDIUM -> mediumQuietInterval;
            case HIGH -> highQuietInterval;
            case EXTREME -> extremeQuietInterval;
        };
        if (presentation.episodes() != null) {
            PresentationConfig.Episodes episodes = presentation.episodes();
            // The longest of the three, not the episode figure alone: this is a
            // floor, and overwriting here would shorten a quiet interval an
            // operator deliberately made longer.
            Duration episodeFloor = Duration.ofMillis(
                    Math.max(episodes.intervalTicks(level), episodes.quietTicks()) * 50L);
            if (episodeFloor.compareTo(configured) > 0) {
                configured = episodeFloor;
            }
        }
        // Distinct cadence must survive the scheduler's check interval, even when
        // all owner-provided quiet periods and per-effect cooldowns are zero.
        int checks = switch (level) {
            case MEDIUM -> 3;
            case HIGH -> 2;
            case EXTREME -> 1;
            case LOW, NEUTRAL, SERENITY -> throw new IllegalArgumentException("This direction has no madness episodes");
        };
        Duration minimum = checkInterval.multipliedBy(checks);
        return configured.compareTo(minimum) < 0 ? minimum : configured;
    }

    public EffectsConfigSection scaled(PsychosisLevel level) {
        return new EffectsConfigSection(checkInterval, silverfish, whisper, creeper, fakeAnnouncement,
                mediumQuietInterval, highQuietInterval, extremeQuietInterval, maxEpisodeTicks,
                presentation.scaled(level), serenity);
    }

    public SingleEffectConfig getEffect(AmbientEffectType type) {
        if (presentation.rules().containsKey(type)) return presentation.rules().get(type).limits();
        return switch (type) {
            case SILVERFISH -> silverfish;
            case WHISPER -> whisper;
            case CREEPER_SOUND -> creeper;
            case FAKE_ANNOUNCEMENT -> fakeAnnouncement;
            default -> presentation.rules().get(type).limits();
        };
    }

    public static EffectsConfigSection defaults() {
        return new EffectsConfigSection(Duration.ofSeconds(1),
                SingleEffectConfig.of(Duration.ofMinutes(10), 6),
                SingleEffectConfig.of(Duration.ofMinutes(5), 6),
                SingleEffectConfig.of(Duration.ofMinutes(8), 6),
                SingleEffectConfig.of(Duration.ofMinutes(15), 6));
    }

    public static EffectsConfigSection load(ConfigurationSection root) {
        Objects.requireNonNull(root, "Root ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("effects");
        if (section == null) return defaults();
        if (section.contains("max-episode-ticks") && !section.isInt("max-episode-ticks")) {
            throw new ConfigValidationException("effects.max-episode-ticks", "Episode bound must be an integer");
        }
        return new EffectsConfigSection(
                DurationParser.parsePositive(section.getString("check-interval", "1s"), "effects.check-interval"),
                loadEffect(section, "silverfish", "10m", 6),
                loadEffect(section, "whisper", "5m", 6),
                loadEffect(section, "creeper", "8m", 6),
                loadEffect(section, "fake-announcement", "15m", 6),
                DurationParser.parseNonNegative(section.getString("quiet-interval.medium", "2m"), "effects.quiet-interval.medium"),
                DurationParser.parseNonNegative(section.getString("quiet-interval.high", "1m"), "effects.quiet-interval.high"),
                DurationParser.parseNonNegative(section.getString("quiet-interval.extreme", "20s"), "effects.quiet-interval.extreme"),
                section.getInt("max-episode-ticks", 200), PresentationConfig.load(root), SerenityEffectsConfig.load(root));
    }

    private static SingleEffectConfig loadEffect(ConfigurationSection section, String key, String cooldownDefault, int capDefault) {
        String prefix = "effects." + key;
        Duration cooldown = DurationParser.parseNonNegative(section.getString(key + ".cooldown", cooldownDefault), prefix + ".cooldown");
        int cap = section.getInt(key + ".session-cap", capDefault);
        if (cap < 0) {
            throw new ConfigValidationException(prefix + ".session-cap", "Session cap must not be negative");
        }
        return SingleEffectConfig.of(cooldown, cap);
    }
}
