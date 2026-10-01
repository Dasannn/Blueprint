package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Typed SB-102–105 settings. No registry-backed Bukkit objects in the decisions. */
public record PresentationConfig(Map<AmbientEffectType, Rule> rules, Sky sky, Particles particles,
                                 Flash flash, Sounds sounds, Episodes episodes) {
    public record Rule(boolean enabled, PsychosisLevel minimumLevel, SingleEffectConfig limits) {
        public boolean allows(PsychosisLevel level) {
            return enabled && level != PsychosisLevel.LOW && level.ordinal() >= minimumLevel.ordinal();
        }
    }
    public record Episodes(long mediumTicks, long highTicks, long extremeTicks, long quietTicks) {
        public long intervalTicks(PsychosisLevel level) {
            return switch (level) {
                case MEDIUM -> mediumTicks;
                case HIGH -> highTicks;
                case EXTREME -> extremeTicks;
                case LOW -> throw new IllegalArgumentException("Low has no episodes");
            };
        }
    }
    public record Sky(String mode, int durationTicks) {}
    public record Particles(String type, String placement, int count, double radius, int durationTicks) {
        public int totalTicks() {
            // Vanilla 26.3: Smoke lifetime <= 40, EndRod <= 71; removal is on the following tick.
            return Math.max(durationTicks, type.equals("smoke") ? 41 : 72);
        }
    }
    public record Flash(String channel, int fadeInTicks, int durationTicks, int fadeOutTicks) {
        public int totalTicks() { return fadeInTicks + durationTicks + fadeOutTicks; }
    }
    public record Sounds(String slot, double forward, double right, double up, int playbackTicks) {}

    public PresentationConfig { rules = Map.copyOf(rules); }

    public long durationTicks(AmbientEffectType type, SoundsConfigSection slots) {
        return switch (type) {
            case SKY -> sky.durationTicks();
            case PARTICLES -> particles.totalTicks();
            case SCREEN_FLASH -> flash.totalTicks();
            case SOURCE_LESS_SOUNDS -> slots.get(sounds.slot()).layers().stream()
                    .filter(layer -> !layer.isSilent()).mapToLong(SoundLayerConfig::delay).max().orElse(0)
                    + sounds.playbackTicks();
            default -> 0;
        };
    }

    public void validateSounds(SoundsConfigSection slots) {
        if (!slots.slots().containsKey(sounds.slot()))
            fail("source-less-sounds.sound-slot", "Must name an existing sounds slot");
        if (slots.get(sounds.slot()).layers().stream().filter(layer -> !layer.isSilent())
                .anyMatch(layer -> layer.delay() > 100L - sounds.playbackTicks()))
            fail("source-less-sounds.playback-ticks", "Final delay plus playback must not exceed 100 ticks");
    }

    public static PresentationConfig load(ConfigurationSection root) {
        Map<AmbientEffectType, Rule> rules = new java.util.EnumMap<>(AmbientEffectType.class);
        for (AmbientEffectType type : Set.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES,
                AmbientEffectType.SCREEN_FLASH, AmbientEffectType.SOURCE_LESS_SOUNDS)) {
            String id = type.configId();
            String path = "effects." + id;
            Set<String> allowed = new java.util.HashSet<>(Set.of("enabled", "minimum-level", "cooldown-ticks", "session-cap"));
            allowed.addAll(switch (type) {
                case SKY -> Set.of("mode", "duration-ticks");
                case PARTICLES -> Set.of("type", "placement", "count", "radius-blocks", "duration-ticks");
                case SCREEN_FLASH -> Set.of("channel", "fade-in-ticks", "duration-ticks", "fade-out-ticks");
                case SOURCE_LESS_SOUNDS -> Set.of("sound-slot", "offset", "playback-ticks");
                default -> Set.of();
            });
            ConfigurationSection section = root.getConfigurationSection(path);
            if (root.contains(path) && section == null) fail(id, "Must be a mapping");
            if (section != null) for (String key : section.getKeys(false))
                if (!allowed.contains(key)) fail(id + "." + key, "Unknown key");
            if (root.contains(path + ".enabled") && !root.isBoolean(path + ".enabled"))
                fail(id + ".enabled", "Must be boolean");
            PsychosisLevel floor = type == AmbientEffectType.SKY ? PsychosisLevel.HIGH : PsychosisLevel.MEDIUM;
            PsychosisLevel minimum;
            try { minimum = PsychosisLevel.valueOf(root.getString(path + ".minimum-level", floor.name()).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw new ConfigValidationException(path + ".minimum-level", "Unknown level"); }
            if (minimum.ordinal() < floor.ordinal()) fail(id + ".minimum-level", "Below catalogue floor");
            rules.put(type, new Rule(root.getBoolean(path + ".enabled", root.contains(path)), minimum,
                    new SingleEffectConfig(Duration.ofMillis(integer(root, id + ".cooldown-ticks", 1200, 1, Integer.MAX_VALUE) * 50L),
                            integer(root, id + ".session-cap", 3, 0, Integer.MAX_VALUE))));
        }
        // Only native particles with a verified finite tail; no unbounded client effects or extra data.
        String particle = choice(root, "particles.type", "smoke", Set.of("smoke", "end_rod"));
        Flash flash = new Flash(choice(root, "screen-flash.channel", "title", Set.of("title", "action-bar")),
                integer(root, "screen-flash.fade-in-ticks", 5, 0, 100),
                integer(root, "screen-flash.duration-ticks", 30, 1, 100),
                integer(root, "screen-flash.fade-out-ticks", 5, 0, 100));
        if (flash.totalTicks() > 100) fail("screen-flash.duration-ticks", "Total including fades must not exceed 100 ticks");
        ConfigurationSection offset = root.getConfigurationSection("effects.source-less-sounds.offset");
        if (root.contains("effects.source-less-sounds.offset") && offset == null) fail("source-less-sounds.offset", "Must be a mapping");
        if (offset != null) for (String key : offset.getKeys(false))
            if (!Set.of("forward-blocks", "right-blocks", "up-blocks").contains(key)) fail("source-less-sounds.offset." + key, "Unknown key");
        Episodes episodes = null;
        if (root.contains("effects.episodes")) {
            long medium = ticks(root, "episodes.medium.interval-ticks", 6000);
            long high = ticks(root, "episodes.high.interval-ticks", 2400);
            long extreme = ticks(root, "episodes.extreme.interval-ticks", 600);
            if (medium <= high || high <= extreme) fail("episodes", "Intervals must decrease medium > high > extreme");
            long quiet = ticks(root, "episodes.quiet-ticks", 20);
            if (quiet > extreme) fail("episodes.quiet-ticks", "Must fit within the extreme interval to preserve level gradation");
            episodes = new Episodes(medium, high, extreme, quiet);
        }
        return new PresentationConfig(rules,
                new Sky(choice(root, "sky.mode", "night", Set.of("night", "storm")), integer(root, "sky.duration-ticks", 60, 1, 100)),
                new Particles(particle, choice(root, "particles.placement", "around", Set.of("around", "beneath")),
                        integer(root, "particles.count", 8, 1, Integer.MAX_VALUE), number(root, "particles.radius-blocks", 1, true),
                        integer(root, "particles.duration-ticks", 40, 1, 100)), flash,
                new Sounds(choice(root, "source-less-sounds.sound-slot", "source-less", null),
                        number(root, "source-less-sounds.offset.forward-blocks", -2, false),
                        number(root, "source-less-sounds.offset.right-blocks", 0, false),
                        number(root, "source-less-sounds.offset.up-blocks", 0, false),
                        integer(root, "source-less-sounds.playback-ticks", 20, 1, 100)), episodes);
    }

    public static PresentationConfig defaults() { return load(new org.bukkit.configuration.MemoryConfiguration()); }
    private static long ticks(ConfigurationSection root, String key, long fallback) {
        Object raw = root.get("effects." + key);
        if (raw != null && !(raw instanceof Integer) && !(raw instanceof Long)) fail(key, "Must be integer ticks");
        long value = raw == null ? fallback : ((Number) raw).longValue();
        if (value < 1 || value > (Long.MAX_VALUE - 20_000L) / 150L) fail(key, "Must be positive ticks within cadence bounds");
        return value;
    }
    private static int integer(ConfigurationSection root, String key, int fallback, int min, int max) {
        String path = "effects." + key;
        if (root.contains(path) && !root.isInt(path)) fail(key, "Must be an integer");
        int value = root.getInt(path, fallback);
        if (value < min || value > max) fail(key, "Out of bounds: " + min + " to " + max);
        return value;
    }
    private static double number(ConfigurationSection root, String key, double fallback, boolean positive) {
        String path = "effects." + key;
        if (root.contains(path) && !(root.get(path) instanceof Number)) fail(key, "Must be numeric");
        double value = root.getDouble(path, fallback);
        if (!Double.isFinite(value) || positive && value <= 0) fail(key, "Must be finite" + (positive ? " and positive" : ""));
        return value;
    }
    private static String choice(ConfigurationSection root, String key, String fallback, Set<String> options) {
        String path = "effects." + key;
        if (root.contains(path) && !root.isString(path)) fail(key, "Must be text");
        String value = root.getString(path, fallback).toLowerCase(Locale.ROOT);
        if (value.isBlank() || options != null && !options.contains(value)) fail(key, "Invalid value");
        return value;
    }
    private static void fail(String key, String reason) { throw new ConfigValidationException("effects." + key, reason); }
}
