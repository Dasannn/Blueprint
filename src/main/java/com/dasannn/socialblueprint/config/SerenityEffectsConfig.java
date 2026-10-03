package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** SB-125 settings only. T-172 owns delivery through the existing renderers. */
public record SerenityEffectsConfig(long intervalTicks, long quietTicks, double observerRange,
                                    Map<String, Rule> rules, int dawnTime, int dawnDuration,
                                    PresentationConfig.Sounds sounds, PresentationConfig.Particles particles,
                                    java.util.List<String> animals, double animalRange, int animalDuration, int followUpdateTicks, double followDistance,
                                    Flowers flowers, int clearDuration, Ambient ambient,
                                    Music music, int phraseDuration, Glow glow) {
    public record Flowers(java.util.List<String> types, double range, int count, int duration) {
        public Flowers { types = java.util.List.copyOf(types); }
    }
    public record Ambient(int count, double radius, int duration) {}
    public record Music(java.util.List<String> keys, float volume, int duration) {
        public Music { keys = java.util.List.copyOf(keys); }
    }
    public record Glow(double range, int count, int duration) {}
    public static final Set<String> FLOWERS = Set.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "blue_orchid", "lily_of_the_valley");
    public static final java.util.List<String> DEFAULT_ANIMALS = java.util.List.of("turtle", "fox", "armadillo", "bee");
    public static final Set<String> EFFECTS = Set.of("dawn", "source-less-sounds", "particles", "apparition",
            "flowers", "clear-sky", "ambient-particles", "music", "warm-phrases", "glowing-animals");
    public record Rule(boolean enabled, double minimumSerenity, long cooldownTicks, int sessionCap) {}
    public SerenityEffectsConfig { rules = Map.copyOf(rules); animals = java.util.List.copyOf(animals); }
    public static SerenityEffectsConfig defaults() { return load(new MemoryConfiguration()); }

    public void validateCeiling(double ceiling) {
        rules.forEach((id, rule) -> {
            if (rule.minimumSerenity() > ceiling) fail(id + ".minimum-serenity", "Must not exceed serenity ceiling");
        });
    }

    public void validateSounds(SoundsConfigSection slots) {
        if (!rules.get("source-less-sounds").enabled()) return;
        if (!slots.slots().containsKey(sounds.slot())) fail("source-less-sounds.sound-slot", "Must name an existing sounds slot");
        if (slots.get(sounds.slot()).layers().stream().filter(layer -> !layer.isSilent())
                .anyMatch(layer -> layer.delay() > 100L - sounds.playbackTicks()))
            fail("source-less-sounds.playback-ticks", "Final sound delay plus playback must not exceed 100 ticks");
    }

    public static SerenityEffectsConfig load(ConfigurationSection root) {
        if (root.contains("effects.serenity") && !root.isConfigurationSection("effects.serenity"))
            throw new ConfigValidationException("effects.serenity", "Must be a mapping");
        Map<String, Rule> rules = new java.util.HashMap<>();
        for (String id : EFFECTS) {
            String key = id + ".enabled";
            if (root.contains(path(key)) && !root.isBoolean(path(key))) fail(key, "Must be boolean");
            rules.put(id, new Rule(root.getBoolean(path(key), true), number(root, id + ".minimum-serenity", 1, true),
                    integer(root, id + ".cooldown-ticks", 6000, 1, Integer.MAX_VALUE),
                    integer(root, id + ".session-cap", 12, 0, Integer.MAX_VALUE)));
        }
        SerenityEffectsConfig result = new SerenityEffectsConfig(integer(root, "episodes.interval-ticks", 6000, 1, Integer.MAX_VALUE),
                integer(root, "episodes.quiet-ticks", 200, 1, Integer.MAX_VALUE),
                number(root, "observer-range-blocks", 16, true), rules,
                integer(root, "dawn.time-ticks", 23000, 0, 23999), integer(root, "dawn.duration-ticks", 200, 1, 200),
                new PresentationConfig.Sounds(choice(root, "source-less-sounds.sound-slot", "serenity-clean", null),
                        number(root, "source-less-sounds.offset.forward-blocks", 0, false),
                        number(root, "source-less-sounds.offset.right-blocks", 0, false),
                        number(root, "source-less-sounds.offset.up-blocks", 0, false),
                        integer(root, "source-less-sounds.playback-ticks", 60, 1, 100)),
                new PresentationConfig.Particles(PresentationConfig.choices(root, path("particles.types"), java.util.List.of("end_rod", "white_smoke"), PresentationConfig.PARTICLE_TAILS.keySet()),
                        choice(root, "particles.placement", "around", Set.of("around", "beneath")),
                        integer(root, "particles.count", 8, 1, PresentationConfig.MAX_PARTICLE_COUNT), number(root, "particles.radius-blocks", 1, true), integer(root, "particles.duration-ticks", 40, 1, 100)),
                PresentationConfig.choices(root, path("apparition.kinds"), DEFAULT_ANIMALS, Set.of("turtle", "fox", "armadillo", "bee", "cat", "wolf")),
                number(root, "apparition.range-blocks", 8, true), integer(root, "apparition.duration-ticks", 300, 1, 400),
                integer(root, "apparition.follow-update-ticks", 5, 1, 20),
                number(root, "apparition.follow-distance-blocks", 6, true),
                new Flowers(PresentationConfig.choices(root, path("flowers.types"),
                        java.util.List.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "allium"), FLOWERS),
                        boundedRange(root, "flowers.range-blocks", 6, 8), integer(root, "flowers.count", 8, 1, 32),
                        integer(root, "flowers.duration-ticks", 200, 1, 400)),
                integer(root, "clear-sky.duration-ticks", 200, 1, 1200),
                new Ambient(integer(root, "ambient-particles.count", 24, 1, 64),
                        boundedRange(root, "ambient-particles.radius-blocks", 2, 8),
                        integer(root, "ambient-particles.duration-ticks", 60, 1, 200)),
                new Music(musicKeys(root), (float) boundedRange(root, "music.volume", .3, 1),
                        integer(root, "music.duration-ticks", 200, 1, 1200)),
                integer(root, "warm-phrases.duration-ticks", 60, 1, 200),
                new Glow(boundedRange(root, "glowing-animals.range-blocks", 8, 32),
                        integer(root, "glowing-animals.count", 8, 1, 32),
                        integer(root, "glowing-animals.duration-ticks", 100, 1, 400)));
        if (result.followDistance() <= 3 + com.dasannn.socialblueprint.feature.effects.SereneEpisode.REACH_MARGIN)
            fail("apparition.follow-distance-blocks", "Must exceed interaction reach plus margin");
        ConfigurationSection section = root.getConfigurationSection("effects.serenity");
        if (section != null) for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key) && !result.leafValues().containsKey(path(key))) fail(key, "Unknown key");
        }
        return result;
    }

    public Map<String, String> leafValues() {
        Map<String, String> values = new java.util.HashMap<>();
        rules.forEach((id, rule) -> {
            values.put(path(id + ".enabled"), String.valueOf(rule.enabled()));
            values.put(path(id + ".minimum-serenity"), String.valueOf(rule.minimumSerenity()));
            values.put(path(id + ".cooldown-ticks"), String.valueOf(rule.cooldownTicks()));
            values.put(path(id + ".session-cap"), String.valueOf(rule.sessionCap()));
        });
        Map<String, Object> details = Map.ofEntries(
                Map.entry("episodes.interval-ticks", intervalTicks), Map.entry("episodes.quiet-ticks", quietTicks),
                Map.entry("observer-range-blocks", observerRange), Map.entry("dawn.time-ticks", dawnTime),
                Map.entry("dawn.duration-ticks", dawnDuration), Map.entry("source-less-sounds.sound-slot", sounds.slot()),
                Map.entry("source-less-sounds.offset.forward-blocks", sounds.forward()),
                Map.entry("source-less-sounds.offset.right-blocks", sounds.right()),
                Map.entry("source-less-sounds.offset.up-blocks", sounds.up()),
                Map.entry("source-less-sounds.playback-ticks", sounds.playbackTicks()),
                Map.entry("particles.types", particles.types()), Map.entry("particles.placement", particles.placement()),
                Map.entry("particles.count", particles.count()), Map.entry("particles.radius-blocks", particles.radius()),
                Map.entry("particles.duration-ticks", particles.durationTicks()), Map.entry("apparition.kinds", animals),
                Map.entry("apparition.range-blocks", animalRange), Map.entry("apparition.duration-ticks", animalDuration),
                Map.entry("apparition.follow-update-ticks", followUpdateTicks), Map.entry("apparition.follow-distance-blocks", followDistance),
                Map.entry("flowers.types", flowers.types()), Map.entry("flowers.range-blocks", flowers.range()),
                Map.entry("flowers.count", flowers.count()), Map.entry("flowers.duration-ticks", flowers.duration()),
                Map.entry("clear-sky.duration-ticks", clearDuration), Map.entry("ambient-particles.count", ambient.count()),
                Map.entry("ambient-particles.radius-blocks", ambient.radius()), Map.entry("ambient-particles.duration-ticks", ambient.duration()),
                Map.entry("music.keys", music.keys()), Map.entry("music.volume", music.volume()), Map.entry("music.duration-ticks", music.duration()),
                Map.entry("warm-phrases.duration-ticks", phraseDuration), Map.entry("glowing-animals.range-blocks", glow.range()),
                Map.entry("glowing-animals.count", glow.count()), Map.entry("glowing-animals.duration-ticks", glow.duration()));
        details.forEach((key, value) -> values.put(path(key), value.toString()));
        return Map.copyOf(values);
    }
    private static double boundedRange(ConfigurationSection root, String key, double fallback, double max) {
        double value = number(root, key, fallback, true);
        if (value > max) fail(key, "Must not exceed " + max);
        return value;
    }
    private static java.util.List<String> musicKeys(ConfigurationSection root) {
        String key = "music.keys";
        Object raw = root.get(path(key), java.util.List.of("minecraft:music.overworld.meadow", "minecraft:music.overworld.cherry_grove"));
        if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) { fail(key, "Must be a nonempty list"); return java.util.List.of(); }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String sound) || !sound.matches("minecraft:music\\.[a-z0-9_.]+"))
                fail(key, "Expected a vanilla music sound key");
            result.add((String) item);
        }
        return java.util.List.copyOf(result);
    }
    private static String path(String key) { return "effects.serenity." + key; }
    private static int integer(ConfigurationSection root, String key, int fallback, int min, int max) {
        if (root.contains(path(key)) && !root.isInt(path(key))) fail(key, "Must be an integer");
        int value = root.getInt(path(key), fallback);
        if (value < min || value > max) fail(key, "Out of bounds");
        return value;
    }
    private static double number(ConfigurationSection root, String key, double fallback, boolean positive) {
        if (root.contains(path(key)) && !(root.get(path(key)) instanceof Number)) fail(key, "Must be numeric");
        double value = root.getDouble(path(key), fallback);
        if (!Double.isFinite(value) || positive && value <= 0) fail(key, "Must be finite" + (positive ? " and positive" : ""));
        return value;
    }
    private static String choice(ConfigurationSection root, String key, String fallback, Set<String> choices) {
        if (root.contains(path(key)) && !root.isString(path(key))) fail(key, "Must be text");
        String value = root.getString(path(key), fallback).toLowerCase(Locale.ROOT);
        if (value.isBlank() || choices != null && !choices.contains(value)) fail(key, "Invalid choice");
        return value;
    }
    private static void fail(String key, String reason) { throw new ConfigValidationException(path(key), reason); }
}
